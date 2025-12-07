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
import java.io.InputStream
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
        fun copyRawTo(index: Int, outputStream: OutputStream) // 新增：用于快速复制文件流
    }

    // 文件夹模式实现
    private class DirImageProvider(private val context: Context, private val dirUri: Uri) : ImageProvider {
        private val docFile = DocumentFile.fromTreeUri(context, dirUri)
            ?: DocumentFile.fromFile(File(dirUri.path!!))

        // 动态获取文件列表，确保替换后能读取到最新状态（如果需要）
        // 但为了性能和顺序稳定性，我们最好缓存文件名列表
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
            } catch (e: Exception) {
                Log.e("BatchAi", "Failed to load image from dir", e)
                null
            }
        }

        override fun copyRawTo(index: Int, outputStream: OutputStream) {
            context.contentResolver.openInputStream(files[index].uri)?.use { input ->
                input.copyTo(outputStream)
            }
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
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output)
                    }
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
                Log.e("BatchAi", "Failed to init ZipProvider", e)
                close()
                throw e
            }
        }

        override val size: Int = entries.size

        override fun getName(index: Int): String = File(entries[index]).name

        override fun getBitmap(index: Int): Bitmap? {
            return try {
                zipFile?.getInputStream(zipFile?.getEntry(entries[index]))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) { null }
        }

        override fun copyRawTo(index: Int, outputStream: OutputStream) {
            zipFile?.getInputStream(zipFile?.getEntry(entries[index]))?.use { input ->
                input.copyTo(outputStream)
            }
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
        // 1. 初始化源图提供者（用于第一次复制）
        val sourceProvider = try {
            createImageProvider(sourceInfo)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { listener.onError("读取源文件出错: ${e.message}") }
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

            // 记录上一个任务的目标目录，用于链式处理（上色 -> 翻译）
            // 如果是第一个任务，上一级目录就是原始 ImageProvider 的逻辑（通过 copyFiles 处理）
            // 但为了统一逻辑，我们采用“先复制到目标目录，然后就地修改”的策略

            val config = AiManagers.currentConfig()
            if (config.apiKey.isNullOrBlank()) {
                withContext(Dispatchers.Main) { listener.onError("未配置 AI API Key") }
                return@withContext
            }

            for (task in tasks) {
                // 2. 准备目标画廊（创建目录、复制文件、注册数据库）
                val targetDir = prepareTargetGallery(sourceInfo, task, initialProvider, results.lastOrNull())

                if (targetDir == null) {
                    withContext(Dispatchers.Main) { listener.onError("无法创建画廊目录") }
                    return@withContext
                }

                // 3. 读取进度（断点续传）
                val progressFile = targetDir.findFile(".ai_progress")
                val startIndex = try {
                    if (progressFile != null) {
                        appCtx.contentResolver.openInputStream(progressFile.uri)?.bufferedReader()?.readText()?.toIntOrNull() ?: 0
                    } else 0
                } catch (e: Exception) { 0 }

                // 4. 创建工作目录的 ImageProvider (读写都在这个新目录进行)
                val workingProvider = DirImageProvider(appCtx, targetDir.uri)
                val total = workingProvider.size

                if (startIndex >= total) {
                    // 任务已完成，直接注册结果并跳过
                    Log.i("BatchAi", "Task ${task.desc} already completed ($startIndex/$total)")
                    continue
                }

                Log.i("BatchAi", "Resuming task ${task.desc} from index $startIndex")

                var failCount = 0

                for (index in startIndex until total) {
                    val fileName = workingProvider.getName(index)

                    withContext(Dispatchers.Main) {
                        listener.onProgress(index + 1, total, "正在处理第 ${index + 1} 页... (任务: ${task.desc})")
                    }

                    // 从工作目录读取图片（此时是上一步的结果，或者是刚刚复制过来的原图）
                    val sourceBitmap = workingProvider.getBitmap(index)

                    if (sourceBitmap == null) {
                        Log.e("BatchAi", "Failed to read bitmap $fileName from working dir")
                        continue
                    }

                    val prompt = if (task == ProcessTarget.ColorOnly) {
                        GeminiManager.PROMPT_COLORIZE
                    } else {
                        GeminiManager.PROMPT_TRANSLATE
                    }

                    // AI 处理
                    val resultBitmap = try {
                        val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                        result.getOrThrow()
                    } catch (e: Exception) {
                        Log.e("BatchAi", "Page ${index + 1} failed: ${e.message}", e)
                        // 【需求一】取消失败后的删除操作。只记录错误，不回滚。
                        // 如果连续失败太多，暂停任务而不是删除文件
                        if (failCount > 4) {
                            withContext(Dispatchers.Main) {
                                listener.onError("连续失败多次，任务已暂停。已处理的图片已保存，下次可继续。错误: ${e.message}")
                            }
                            return@withContext
                        }
                        failCount++
                        sourceBitmap // 失败回退使用原图
                    }

                    // 【逻辑核心】就地替换：保存回目标目录，覆盖原文件
                    saveBitmapToUri(targetDir, fileName, resultBitmap)

                    // 【需求三】保存进度标记
                    saveProgress(targetDir, index + 1)

                    if (resultBitmap != sourceBitmap) {
                        delay(1000) // 基础限流
                        failCount = 0 // 重置失败计数
                    }
                }

                // 任务完成，删除进度文件
                try { targetDir.findFile(".ai_progress")?.delete() } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) { listener.onComplete(results) }
        }
    }

    private suspend fun prepareTargetGallery(
        sourceInfo: DownloadInfo,
        task: ProcessTarget,
        initialProvider: ImageProvider,
        previousResult: DownloadInfo? // 如果是链式任务的第二步，输入来源于上一步的结果
    ): DocumentFile? {
        // 1. 计算新目录名称
        val suffix = when (task) {
            ProcessTarget.ColorOnly -> "[AI-Color]"
            ProcessTarget.TranslateOnly -> "[AI-TL]"
            ProcessTarget.TranslateFromColor -> "[AI-Color+TL]"
        }
        val newTitle = "$suffix ${sourceInfo.title ?: sourceInfo.gid}"
        val newDirName = FileUtils.sanitizeFilename(newTitle)

        // 2. 寻找或创建目录
        // [修复] downloadLocation 是顶层属性，直接使用
        val downloadDirUri = downloadLocation.toUri()
        val rootDoc = DocumentFile.fromTreeUri(appCtx, downloadDirUri)
            ?: DocumentFile.fromFile(File(downloadDirUri.path ?: Environment.getExternalStorageDirectory().path))

        var targetDir = rootDoc.findFile(newDirName)
        val isResume = targetDir != null

        if (targetDir == null) {
            targetDir = rootDoc.createDirectory(newDirName) ?: return null
        }

        // 3. 【需求二】提前注册到画廊列表
        // 无论是否是续传，都尝试注册一下，确保它出现在列表中
        val registeredInfo = registerAsDownload(targetDir!!, sourceInfo.findBaseInfo(), task)

        // 4. 如果是新建目录，或者目录为空，执行“全量复制”
        // 逻辑：如果是链式任务的第二步(TranslateFromColor)，应该从上一步的目录复制
        // 如果是第一步，从 initialProvider 复制
        val filesCount = targetDir.listFiles().size
        if (!isResume || filesCount == 0) {
            Log.i("BatchAi", "Initializing gallery content for ${task.desc}...")

            if (task == ProcessTarget.TranslateFromColor && previousResult != null) {
                // 从上一步的结果目录复制
                val prevDir = previousResult.downloadDir?.let { DocumentFile.fromTreeUri(appCtx, it.toUri()) }
                // [修复] 使用 toString() 获取路径字符串，因为 Okio Path 没有 .path 属性
                    ?: DocumentFile.fromFile(File(previousResult.downloadDir!!.toString()))

                val prevFiles = prevDir.listFiles().filter { it.name?.endsWith(".ai_progress") == false }

                prevFiles.forEach { file ->
                    val destFile = targetDir.createFile(file.type ?: "image/jpeg", file.name!!)
                    if (destFile != null) {
                        appCtx.contentResolver.openInputStream(file.uri)?.use { input ->
                            appCtx.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } else {
                // 从源头复制 (Zip 或 文件夹)
                for (i in 0 until initialProvider.size) {
                    val name = initialProvider.getName(i)
                    val destFile = targetDir.createFile("image/jpeg", name)
                    if (destFile != null) {
                        appCtx.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                            initialProvider.copyRawTo(i, output)
                        }
                    }
                }
            }
        }

        return targetDir
    }

    private fun saveProgress(dir: DocumentFile, index: Int) {
        try {
            val file = dir.findFile(".ai_progress") ?: dir.createFile("text/plain", ".ai_progress")
            file?.let {
                appCtx.contentResolver.openOutputStream(it.uri, "wt")?.use { os ->
                    os.write(index.toString().toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.w("BatchAi", "Failed to save progress", e)
        }
    }

    private fun createTargetDir(sourceInfo: DownloadInfo, task: ProcessTarget): DocumentFile? {
        // 此方法已整合到 prepareTargetGallery 中，保留空实现或删除均可，为防报错保留个空壳
        return null
    }

    private fun createImageProvider(sourceInfo: DownloadInfo): ImageProvider? {
        val downloadDirUri = sourceInfo.downloadDir?.toUri()
        val archiveUri = sourceInfo.archiveFile?.toUri()

        return when {
            archiveUri != null -> {
                val exists = try {
                    appCtx.contentResolver.openFileDescriptor(archiveUri, "r")?.close()
                    true
                } catch (e: Exception) { false }
                if (exists) ZipImageProvider(appCtx, archiveUri) else null
            }
            downloadDirUri != null -> {
                val doc = DocumentFile.fromTreeUri(appCtx, downloadDirUri)
                if (doc != null && doc.exists() && doc.isDirectory) {
                    DirImageProvider(appCtx, downloadDirUri)
                } else if (File(downloadDirUri.path ?: "").exists()) {
                    DirImageProvider(appCtx, downloadDirUri)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun saveBitmapToUri(dir: DocumentFile, name: String, bitmap: Bitmap) {
        // 查找现有文件并覆盖
        val file = dir.findFile(name) ?: dir.createFile("image/jpeg", name) ?: return
        appCtx.contentResolver.openOutputStream(file.uri, "wt")?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
        }
    }

    private suspend fun registerAsDownload(
        targetDir: DocumentFile,
        sourceInfo: BaseGalleryInfo,
        task: ProcessTarget,
    ): DownloadInfo? {
        val newGid = when (task) {
            ProcessTarget.ColorOnly -> sourceInfo.gid * 10 + 1
            ProcessTarget.TranslateOnly -> sourceInfo.gid * 10 + 2
            ProcessTarget.TranslateFromColor -> sourceInfo.gid * 10 + 3
        }

        // 检查是否已存在，使用 DownloadManager 缓存
        // [修复] EhDB 没有 getDownloadInfo，使用 DownloadManager.getDownloadInfo
        val existInfo = DownloadManager.getDownloadInfo(newGid)
        if (existInfo != null) return existInfo

        val newInfo = BaseGalleryInfo(
            gid = newGid,
            token = sourceInfo.token,
            title = targetDir.name ?: "Unknown",
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

        val dirname = targetDir.name ?: return null
        EhDB.putDownloadDirname(newGid, dirname)
        val info = DownloadInfo(newInfo.asEntity(), dirname)

        // 【需求二】标记为 AI进行中
        info.state = DownloadInfo.STATE_FINISH // 设为 Finish 才能在列表看到
        info.label = "AI进行中" // 使用标签区分状态

        val pageCount = targetDir.listFiles().size
        info.finished = pageCount
        info.total = pageCount
        info.downloaded = pageCount

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