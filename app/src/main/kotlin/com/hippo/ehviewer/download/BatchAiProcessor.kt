package com.hippo.ehviewer.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.data.model.findBaseInfo
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.toUri
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.util.AiManagers
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.GeminiManager
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BatchAiProcessor {

    interface ProgressListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onComplete(newInfo: List<DownloadInfo>)
        fun onError(error: String)
    }

    private interface ImageProvider : AutoCloseable {
        val size: Int
        fun getName(index: Int): String
        fun getBitmap(index: Int): Bitmap?
        fun copyRawTo(index: Int, outputStream: OutputStream)
    }

    // 文件夹模式实现
    private class DirImageProvider(private val context: Context, private val dirUri: Uri) : ImageProvider {
        private val docFile = DocumentFile.fromTreeUri(context, dirUri)
            ?: DocumentFile.fromFile(File(dirUri.path!!))

        private val files = docFile.listFiles().filter { file ->
            val name = file.name?.lowercase().orEmpty()
            name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp")
        }.sortedBy { it.name }

        override val size: Int = files.size
        override fun getName(index: Int) = files[index].name ?: "image_$index.jpg"

        override fun getBitmap(index: Int): Bitmap? {
            return try {
                context.contentResolver.openInputStream(files[index].uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) { null }
        }

        override fun copyRawTo(index: Int, outputStream: OutputStream) {
            context.contentResolver.openInputStream(files[index].uri)?.use { it.copyTo(outputStream) }
        }

        override fun close() {}
    }

    // 压缩包模式实现
    private class ZipImageProvider(private val context: Context, private val zipUri: Uri) : ImageProvider {
        private var tempFile: File? = null
        private var zipFile: ZipFile? = null
        private var entries: List<String> = emptyList()

        init {
            try {
                val temp = File.createTempFile("ai_process_", ".tmp", context.cacheDir)
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                tempFile = temp
                val zip = ZipFile(temp)
                zipFile = zip
                entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter {
                        val name = it.name.lowercase()
                        name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp")
                    }
                    .map { it.name }
                    .sorted()
                    .toList()
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        override val size: Int = entries.size
        override fun getName(index: Int): String = File(entries[index]).name

        override fun getBitmap(index: Int): Bitmap? {
            return try {
                zipFile?.getInputStream(zipFile?.getEntry(entries[index]))?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
        }

        override fun copyRawTo(index: Int, outputStream: OutputStream) {
            zipFile?.getInputStream(zipFile?.getEntry(entries[index]))?.use { it.copyTo(outputStream) }
        }

        override fun close() {
            try { zipFile?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    suspend fun processGallery(
        sourceInfo: DownloadInfo,
        mode: AiProcessMode,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val sourceProvider = try {
            createImageProvider(sourceInfo)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { listener.onError("读取源文件失败: ${e.message}") }
            return@withContext
        }

        if (sourceProvider == null || sourceProvider.size == 0) {
            withContext(Dispatchers.Main) { listener.onError("找不到源图片") }
            return@withContext
        }

        sourceProvider.use { initialProvider ->
            val tasks = when (mode) {
                AiProcessMode.COLOR -> listOf(ProcessTarget.ColorOnly)
                AiProcessMode.TRANSLATE -> listOf(ProcessTarget.TranslateOnly)
                AiProcessMode.FULL -> listOf(ProcessTarget.ColorOnly, ProcessTarget.TranslateFromColor)
                else -> emptyList()
            }

            val results = mutableListOf<DownloadInfo>()
            val config = AiManagers.currentConfig()

            if (config.apiKey.isNullOrBlank()) {
                withContext(Dispatchers.Main) { listener.onError("未配置 AI API Key") }
                return@withContext
            }

            var previousResultInfo: DownloadInfo? = null

            for (task in tasks) {
                // 如果是链式第二步，应该基于上一步的结果作为源
                val currentSourceProvider = if (task == ProcessTarget.TranslateFromColor && previousResultInfo != null) {
                    null
                } else {
                    initialProvider
                }

                // 2. 准备目标画廊
                val targetDir = prepareTargetGallery(sourceInfo, task, currentSourceProvider, previousResultInfo)

                if (targetDir == null) {
                    withContext(Dispatchers.Main) { listener.onError("无法创建目标目录") }
                    return@withContext
                }

                // 3. 注册到列表
                val downloadInfo = registerAsDownload(targetDir, sourceInfo.findBaseInfo(), task)
                results.add(downloadInfo)
                previousResultInfo = downloadInfo

                // 4. 读取断点进度
                val progressFile = targetDir.findFile(".ai_progress")
                val startIndex = try {
                    if (progressFile != null) {
                        appCtx.contentResolver.openInputStream(progressFile.uri)?.bufferedReader()?.readText()?.trim()?.toIntOrNull() ?: 0
                    } else 0
                } catch (e: Exception) { 0 }

                // 5. 准备处理
                val workingProvider = DirImageProvider(appCtx, targetDir.uri)
                val total = workingProvider.size

                if (startIndex >= total) {
                    Log.i("BatchAi", "Task ${task.desc} already finished.")
                    continue
                }

                Log.i("BatchAi", "Starting task ${task.desc} from index $startIndex")
                var failCount = 0

                for (index in startIndex until total) {
                    val fileName = workingProvider.getName(index)

                    withContext(Dispatchers.Main) {
                        listener.onProgress(index + 1, total, "正在处理 ${index + 1}/$total (任务: ${task.desc})")
                    }

                    val sourceBitmap = workingProvider.getBitmap(index)
                    if (sourceBitmap == null) {
                        Log.e("BatchAi", "Cannot read bitmap $fileName")
                        continue
                    }

                    val prompt = if (task == ProcessTarget.ColorOnly) GeminiManager.PROMPT_COLORIZE else GeminiManager.PROMPT_TRANSLATE

                    val resultBitmap = try {
                        val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                        result.getOrThrow()
                    } catch (e: Exception) {
                        Log.e("BatchAi", "Page $index error", e)
                        // 捕获 Unreachable 或其他严重错误
                        val errMsg = e.message ?: "Unknown Error"
                        if (errMsg.contains("Unreachable")) {
                            withContext(Dispatchers.Main) {
                                listener.onError("遇到内部错误(Unreachable)，请尝试更换 AI 模型或 API 格式。")
                            }
                            return@withContext
                        }

                        if (failCount > 4) {
                            withContext(Dispatchers.Main) {
                                listener.onError("连续失败多次，任务暂停。进度已保存。错误: $errMsg")
                            }
                            return@withContext
                        }
                        failCount++
                        sourceBitmap // 回退
                    }

                    saveBitmapToUri(targetDir, fileName, resultBitmap)
                    saveProgress(targetDir, index + 1)

                    if (resultBitmap != sourceBitmap) {
                        delay(1000)
                        failCount = 0
                    }
                }

                try { targetDir.findFile(".ai_progress")?.delete() } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) { listener.onComplete(results) }
        }
    }

    private suspend fun prepareTargetGallery(
        sourceInfo: DownloadInfo,
        task: ProcessTarget,
        initialProvider: ImageProvider?,
        previousResult: DownloadInfo?
    ): DocumentFile? {
        val suffix = when (task) {
            ProcessTarget.ColorOnly -> "[AI-Color]"
            ProcessTarget.TranslateOnly -> "[AI-TL]"
            ProcessTarget.TranslateFromColor -> "[AI-Color+TL]"
        }

        // 【核心修复】更强力的文件名清洗，防止换行符等导致“名字不对”的文件夹
        val rawTitle = sourceInfo.title ?: sourceInfo.gid.toString()
        val cleanTitle = rawTitle.replace("\n", " ").replace("\r", "").trim().take(50) // 限制长度
        val newTitle = "$suffix $cleanTitle"
        val safeDirName = FileUtils.sanitizeFilename(newTitle)

        // 【核心修复】获取下载根目录
        val downloadDirUri = downloadLocation.toUri()
        val rootDoc = DocumentFile.fromTreeUri(appCtx, downloadDirUri)
            ?: DocumentFile.fromFile(File(downloadDirUri.path ?: Environment.getExternalStorageDirectory().path))

        // 【核心修复】智能查找：不只匹配名字，如果能找到包含 GID 和 Suffix 的现有目录，优先复用
        // 这样可以找回之前因为名字乱码而“丢失”的进度
        var targetDir = rootDoc.findFile(safeDirName)

        if (targetDir == null) {
            // 尝试模糊搜索：遍历目录，寻找包含 "[AI-Color]" 和 GID 的文件夹
            val searchKey = "${sourceInfo.gid}"
            val existing = rootDoc.listFiles().find {
                it.isDirectory && (it.name?.contains(suffix) == true) && (it.name?.contains(searchKey) == true)
            }
            if (existing != null) {
                Log.i("BatchAi", "Found existing directory by fuzzy match: ${existing.name}")
                targetDir = existing
            }
        }

        val isResume = targetDir != null && (targetDir.listFiles().isNotEmpty())

        if (targetDir == null) {
            targetDir = rootDoc.createDirectory(safeDirName) ?: return null
        }

        if (!isResume) {
            Log.i("BatchAi", "Initializing target dir: ${targetDir.name}")
            if (task == ProcessTarget.TranslateFromColor && previousResult != null) {
                val prevUri = previousResult.downloadDir?.toUri() ?: return null
                val prevDoc = DocumentFile.fromTreeUri(appCtx, prevUri) ?: return null
                prevDoc.listFiles().forEach { f ->
                    if (f.name?.endsWith(".ai_progress") == false && f.isFile) {
                        copyFile(f, targetDir!!)
                    }
                }
            } else if (initialProvider != null) {
                for (i in 0 until initialProvider.size) {
                    val name = initialProvider.getName(i)
                    val destFile = targetDir.createFile("image/jpeg", name)
                    if (destFile != null) {
                        appCtx.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                            initialProvider.copyRawTo(i, out)
                        }
                    }
                }
            }
        }
        return targetDir
    }

    private fun copyFile(src: DocumentFile, destDir: DocumentFile) {
        val dest = destDir.createFile(src.type ?: "application/octet-stream", src.name ?: "temp") ?: return
        try {
            appCtx.contentResolver.openInputStream(src.uri)?.use { input ->
                appCtx.contentResolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e("BatchAi", "Copy failed", e)
        }
    }

    private fun createImageProvider(info: DownloadInfo): ImageProvider? {
        val dirUri = info.downloadDir?.toUri()
        val archiveUri = info.archiveFile?.toUri()

        return when {
            archiveUri != null -> ZipImageProvider(appCtx, archiveUri)
            dirUri != null -> DirImageProvider(appCtx, dirUri)
            else -> null
        }
    }

    private fun saveBitmapToUri(dir: DocumentFile, name: String, bitmap: Bitmap) {
        val file = dir.findFile(name) ?: dir.createFile("image/jpeg", name) ?: return
        appCtx.contentResolver.openOutputStream(file.uri, "wt")?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
    }

    private fun saveProgress(dir: DocumentFile, index: Int) {
        try {
            val file = dir.findFile(".ai_progress") ?: dir.createFile("text/plain", ".ai_progress")
            file?.let {
                appCtx.contentResolver.openOutputStream(it.uri, "wt")?.use { out ->
                    out.write(index.toString().toByteArray())
                }
            }
        } catch (e: Exception) { Log.w("BatchAi", "Save progress failed", e) }
    }

    private suspend fun registerAsDownload(
        targetDir: DocumentFile,
        sourceInfo: BaseGalleryInfo,
        task: ProcessTarget
    ): DownloadInfo {
        val newGid = when (task) {
            ProcessTarget.ColorOnly -> sourceInfo.gid * 10 + 1
            ProcessTarget.TranslateOnly -> sourceInfo.gid * 10 + 2
            ProcessTarget.TranslateFromColor -> sourceInfo.gid * 10 + 3
        }

        val cached = DownloadManager.getDownloadInfo(newGid)
        if (cached != null) return cached

        val newInfo = BaseGalleryInfo(
            gid = newGid,
            token = sourceInfo.token,
            title = targetDir.name ?: "AI Generated",
            titleJpn = sourceInfo.titleJpn,
            thumbKey = sourceInfo.thumbKey,
            category = sourceInfo.category,
            posted = sourceInfo.posted,
            uploader = sourceInfo.uploader,
            disowned = sourceInfo.disowned,
            rating = sourceInfo.rating,
            rated = sourceInfo.rated,
            simpleTags = sourceInfo.simpleTags,
            pages = sourceInfo.pages,
            thumbWidth = sourceInfo.thumbWidth,
            thumbHeight = sourceInfo.thumbHeight,
            simpleLanguage = sourceInfo.simpleLanguage,
            favoriteSlot = sourceInfo.favoriteSlot,
            favoriteName = sourceInfo.favoriteName,
            favoriteNote = sourceInfo.favoriteNote,
        )

        val dirname = targetDir.name ?: "unknown"
        EhDB.putDownloadDirname(newGid, dirname)
        val info = DownloadInfo(newInfo.asEntity(), dirname)

        info.state = DownloadInfo.STATE_FINISH
        info.label = "AI处理中..."
        info.total = targetDir.listFiles().size
        info.finished = 0

        DownloadManager.addFinishedDownload(listOf(info))
        return info
    }
}

enum class AiProcessMode {
    NONE,
    COLOR,
    TRANSLATE,
    FULL,
}

private enum class ProcessTarget(val desc: String) {
    ColorOnly("上色"),
    TranslateOnly("翻译"),
    TranslateFromColor("翻译(基于上色)"),
}