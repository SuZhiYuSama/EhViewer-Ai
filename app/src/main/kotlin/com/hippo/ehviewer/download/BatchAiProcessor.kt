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

    // 抽象图片提供者，统一处理文件夹和压缩包
    private interface ImageProvider : AutoCloseable {
        val size: Int
        fun getName(index: Int): String
        fun getBitmap(index: Int): Bitmap?
        fun copyRawTo(index: Int, outputStream: OutputStream)
    }

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
        // 1. 准备源文件读取器
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

            // 用于链式任务传递结果（上色 -> 翻译）
            var previousResultInfo: DownloadInfo? = null

            for (task in tasks) {
                // 2. 准备目标画廊（核心逻辑：新建目录、注册DB、复制原图）
                // 如果是链式第二步，应该基于上一步的结果作为源
                val currentSourceProvider = if (task == ProcessTarget.TranslateFromColor && previousResultInfo != null) {
                    // 这种情况下，源头变成了上一步生成的文件夹
                    // 但为了简单，prepareTargetGallery 内部会处理文件复制
                    null
                } else {
                    initialProvider
                }

                val targetDir = prepareTargetGallery(sourceInfo, task, currentSourceProvider, previousResultInfo)

                if (targetDir == null) {
                    withContext(Dispatchers.Main) { listener.onError("无法创建目标目录，请检查权限") }
                    return@withContext
                }

                // 3. 注册或获取 DownloadInfo，让用户立刻可见
                val downloadInfo = registerAsDownload(targetDir, sourceInfo.findBaseInfo(), task)
                results.add(downloadInfo)
                previousResultInfo = downloadInfo

                // 4. 读取断点进度
                val progressFile = targetDir.findFile(".ai_progress")
                val startIndex = try {
                    if (progressFile != null) {
                        appCtx.contentResolver.openInputStream(progressFile.uri)?.bufferedReader()?.readText()?.toIntOrNull() ?: 0
                    } else 0
                } catch (e: Exception) { 0 }

                // 5. 在新目录上进行“原地修改”
                // 此时 targetDir 里已经有了所有图片（要么是刚复制的，要么是上次处理一半的）
                val workingProvider = DirImageProvider(appCtx, targetDir.uri)
                val total = workingProvider.size

                if (startIndex >= total) {
                    Log.i("BatchAi", "Task ${task.desc} already finished. Skipping.")
                    continue
                }

                Log.i("BatchAi", "Starting task ${task.desc} from index $startIndex")
                var failCount = 0

                for (index in startIndex until total) {
                    val fileName = workingProvider.getName(index)

                    withContext(Dispatchers.Main) {
                        listener.onProgress(index + 1, total, "正在处理 ${index + 1}/$total (任务: ${task.desc})")
                    }

                    // 读取当前图片（可能是原图，也可能是待处理图）
                    val sourceBitmap = workingProvider.getBitmap(index)
                    if (sourceBitmap == null) {
                        Log.e("BatchAi", "Cannot read bitmap $fileName")
                        continue
                    }

                    val prompt = if (task == ProcessTarget.ColorOnly) GeminiManager.PROMPT_COLORIZE else GeminiManager.PROMPT_TRANSLATE

                    // AI 请求
                    val resultBitmap = try {
                        val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                        result.getOrThrow()
                    } catch (e: Exception) {
                        Log.e("BatchAi", "Page $index error: ${e.message}")
                        // 失败策略：保留原图，记录错误。如果连续失败太多则暂停
                        if (failCount > 4) {
                            withContext(Dispatchers.Main) {
                                listener.onError("连续失败多次，任务暂停。进度已保存。错误: ${e.message}")
                            }
                            return@withContext
                        }
                        failCount++
                        sourceBitmap // 回退使用原图，保证文件不损坏
                    }

                    // 覆盖保存
                    saveBitmapToUri(targetDir, fileName, resultBitmap)

                    // 记录进度
                    saveProgress(targetDir, index + 1)

                    if (resultBitmap != sourceBitmap) {
                        delay(1000) // 避免速率限制
                        failCount = 0
                    }
                }

                // 任务完成，清理进度文件
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
        // 1. 确定目录名
        val suffix = when (task) {
            ProcessTarget.ColorOnly -> "[AI-Color]"
            ProcessTarget.TranslateOnly -> "[AI-TL]"
            ProcessTarget.TranslateFromColor -> "[AI-Color+TL]"
        }
        val newTitle = "$suffix ${sourceInfo.title ?: sourceInfo.gid}"
        val newDirName = FileUtils.sanitizeFilename(newTitle)

        // 2. 获取下载根目录 (使用顶层变量 downloadLocation)
        val downloadDirUri = downloadLocation.toUri()
        val rootDoc = DocumentFile.fromTreeUri(appCtx, downloadDirUri)
            ?: DocumentFile.fromFile(File(downloadDirUri.path ?: Environment.getExternalStorageDirectory().path))

        // 3. 检查目录是否存在
        var targetDir = rootDoc.findFile(newDirName)
        val isResume = targetDir != null && (targetDir.listFiles().isNotEmpty())

        if (targetDir == null) {
            targetDir = rootDoc.createDirectory(newDirName) ?: return null
        }

        // 4. 如果不是断点续传（目录为空），则执行全量复制初始化
        if (!isResume) {
            Log.i("BatchAi", "Initializing target dir: $newDirName")

            if (task == ProcessTarget.TranslateFromColor && previousResult != null) {
                // 链式任务：从上一步的结果目录复制
                val prevUri = previousResult.downloadDir?.toUri() ?: return null
                val prevDoc = DocumentFile.fromTreeUri(appCtx, prevUri) ?: return null

                prevDoc.listFiles().forEach { f ->
                    if (f.name?.endsWith(".ai_progress") == false && f.isFile) {
                        copyFile(f, targetDir!!)
                    }
                }
            } else if (initialProvider != null) {
                // 初始任务：从 ImageProvider (Zip/Dir) 复制
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
        // "wt" 模式会截断现有文件进行覆盖
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

        // 尝试从 DownloadManager 缓存获取，如果已存在直接返回
        val cached = DownloadManager.getDownloadInfo(newGid)
        if (cached != null) return cached

        // 构建新信息
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

        // 关键：设为 FINISH 状态才能显示在列表中，但用 Label 区分
        info.state = DownloadInfo.STATE_FINISH
        info.label = "AI处理中..."
        info.total = targetDir.listFiles().size
        info.finished = 0 // 或者读取进度

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