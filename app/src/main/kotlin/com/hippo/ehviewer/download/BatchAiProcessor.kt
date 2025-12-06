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
    }

    // 文件夹模式实现（支持 File 和 SAF Uri）
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
            } catch (e: Exception) {
                Log.e("BatchAi", "Failed to load image from dir", e)
                null
            }
        }
        override fun close() {}
    }

    // 压缩包模式实现（支持流式复制处理 SAF Uri）
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

        override fun getName(index: Int): String {
            return File(entries[index]).name
        }

        override fun getBitmap(index: Int): Bitmap? {
            return try {
                val zip = zipFile ?: return null
                val entry = zip.getEntry(entries[index])
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }

        override fun close() {
            try {
                zipFile?.close()
            } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    suspend fun processGallery(
        sourceInfo: DownloadInfo,
        mode: AiProcessMode,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val imageProvider = try {
            val downloadDirUri = sourceInfo.downloadDir?.toUri()
            val archiveUri = sourceInfo.archiveFile?.toUri()

            when {
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
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { listener.onError("读取源文件出错: ${e.message}") }
            return@withContext
        }

        if (imageProvider == null) {
            withContext(Dispatchers.Main) { listener.onError("找不到源文件（请检查下载路径设置或文件是否被删除）") }
            return@withContext
        }

        imageProvider.use { provider ->
            if (provider.size == 0) {
                withContext(Dispatchers.Main) { listener.onError("源文件中没有可识别的图片") }
                return@withContext
            }

            val tasks = when (mode) {
                AiProcessMode.COLOR -> listOf(ProcessTarget.ColorOnly)
                AiProcessMode.TRANSLATE -> listOf(ProcessTarget.TranslateOnly)
                AiProcessMode.FULL -> listOf(ProcessTarget.ColorOnly, ProcessTarget.TranslateFromColor)
                else -> emptyList()
            }

            val results = mutableListOf<DownloadInfo>()
            var previousDir: DocumentFile? = null

            val config = AiManagers.currentConfig()
            if (config.apiKey.isNullOrBlank()) {
                withContext(Dispatchers.Main) { listener.onError("未配置 AI API Key") }
                return@withContext
            }

            for (task in tasks) {
                val targetDir = createTargetDir(sourceInfo, task)
                if (targetDir == null) {
                    withContext(Dispatchers.Main) { listener.onError("无法创建目标文件夹，请检查存储权限") }
                    return@withContext
                }

                val total = provider.size
                var failCount = 0

                for (index in 0 until total) {
                    val fileName = provider.getName(index)

                    withContext(Dispatchers.Main) {
                        listener.onProgress(index + 1, total, "正在处理第 ${index + 1} 页... (任务: ${task.desc})")
                    }

                    val sourceBitmap = when (task) {
                        ProcessTarget.TranslateFromColor -> {
                            val file = previousDir?.findFile(fileName)
                            if (file != null) {
                                appCtx.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it) }
                            } else {
                                null
                            }
                        }
                        else -> provider.getBitmap(index)
                    }

                    if (sourceBitmap == null) {
                        Log.e("BatchAi", "Failed to decode bitmap for $fileName")
                        continue
                    }

                    val prompt = if (task == ProcessTarget.ColorOnly) {
                        GeminiManager.PROMPT_COLORIZE
                    } else {
                        GeminiManager.PROMPT_TRANSLATE
                    }

                    val resultBitmap = try {
                        val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                        result.getOrThrow()
                    } catch (e: Exception) {
                        Log.e("BatchAi", "Page ${index + 1} failed: ${e.message}", e)
                        if (index == 0 || failCount > 2) {
                            withContext(Dispatchers.Main) {
                                listener.onError("AI 处理失败: ${e.message}")
                            }
                            try { targetDir.delete() } catch (_: Exception) {}
                            return@withContext
                        }
                        failCount++
                        sourceBitmap
                    }

                    saveBitmapToUri(targetDir, fileName, resultBitmap)

                    // 关键修改：即使是在协程中，也增加延时，确保 API 不会因速率过快而报错 (429)
                    if (resultBitmap != sourceBitmap) {
                        delay(2000)
                    }
                }

                val downloadInfo = registerAsDownload(targetDir, sourceInfo.findBaseInfo(), task)
                if (downloadInfo != null) {
                    results += downloadInfo
                }
                previousDir = targetDir
            }

            withContext(Dispatchers.Main) { listener.onComplete(results) }
        }
    }

    private fun createTargetDir(
        sourceInfo: DownloadInfo,
        task: ProcessTarget,
    ): DocumentFile? {
        val suffix = when (task) {
            ProcessTarget.ColorOnly -> "[AI-Color]"
            ProcessTarget.TranslateOnly -> "[AI-TL]"
            ProcessTarget.TranslateFromColor -> "[AI-Color+TL]"
        }
        val newTitle = "$suffix ${sourceInfo.title ?: sourceInfo.gid}"
        val newDirName = FileUtils.sanitizeFilename(newTitle)

        // 优先使用源文件的父目录
        val downloadDirUri = sourceInfo.downloadDir?.toUri()
        // 【修正】直接访问 downloadLocation，不需要 DownloadManager. 前缀
        val defaultDownloadUri = downloadLocation.toUri()

        val parentUri = if (downloadDirUri != null) {
            val file = File(downloadDirUri.path ?: "")
            if (file.exists()) {
                Uri.fromFile(file.parentFile)
            } else {
                // 如果是 SAF 路径，尝试获取其父级比较困难，简单起见使用全局配置的下载目录
                defaultDownloadUri
            }
        } else {
            defaultDownloadUri
        }

        // 使用 DocumentFile 操作
        val rootDoc = DocumentFile.fromTreeUri(appCtx, parentUri)
            ?: DocumentFile.fromFile(File(parentUri.path ?: Environment.getExternalStorageDirectory().path))

        return rootDoc.findFile(newDirName) ?: rootDoc.createDirectory(newDirName)
    }

    private fun saveBitmapToUri(dir: DocumentFile, name: String, bitmap: Bitmap) {
        val file = dir.findFile(name) ?: dir.createFile("image/jpeg", name) ?: return
        appCtx.contentResolver.openOutputStream(file.uri)?.use { os ->
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
        info.state = DownloadInfo.STATE_FINISH
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