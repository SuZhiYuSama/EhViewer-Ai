package com.hippo.ehviewer.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.data.model.findBaseInfo
import com.ehviewer.core.database.model.DownloadInfo
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

    private class DirImageProvider(private val dir: File) : ImageProvider {
        private val files = dir.listFiles { file ->
            val name = file.name.lowercase()
            name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp")
        }?.sortedBy(File::getName) ?: emptyList()

        override val size: Int = files.size
        override fun getName(index: Int) = files[index].name
        override fun getBitmap(index: Int) = BitmapFactory.decodeFile(files[index].absolutePath)
        override fun close() {}
    }

    private class ZipImageProvider(private val zipFile: File) : ImageProvider {
        private val zip = ZipFile(zipFile)
        private val entries = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter {
                val name = it.name.lowercase()
                name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp")
            }
            .sortedBy { it.name }
            .toList()

        override val size: Int = entries.size
        override fun getName(index: Int) = File(entries[index].name).name
        override fun getBitmap(index: Int): Bitmap? {
            return try {
                zip.getInputStream(entries[index]).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        override fun close() = zip.close()
    }

    suspend fun processGallery(
        sourceInfo: DownloadInfo,
        mode: AiProcessMode,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val imageProvider = try {
            val archive = sourceInfo.archiveFile?.toFile()
            val dir = sourceInfo.downloadDir?.toFile()

            when {
                archive != null && archive.exists() -> ZipImageProvider(archive)
                dir != null && dir.exists() -> DirImageProvider(dir)
                else -> {
                    withContext(Dispatchers.Main) { listener.onError("找不到源文件 (未发现目录或压缩包)") }
                    return@withContext
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { listener.onError("读取源文件失败: ${e.message}") }
            return@withContext
        }

        imageProvider.use { provider ->
            if (provider.size == 0) {
                withContext(Dispatchers.Main) { listener.onError("源文件中没有图片") }
                return@withContext
            }

            val tasks = when (mode) {
                AiProcessMode.COLOR -> listOf(ProcessTarget.ColorOnly)
                AiProcessMode.TRANSLATE -> listOf(ProcessTarget.TranslateOnly)
                AiProcessMode.FULL -> listOf(ProcessTarget.ColorOnly, ProcessTarget.TranslateFromColor)
                else -> emptyList()
            }

            val results = mutableListOf<DownloadInfo>()
            var previousDir: File? = null

            val config = AiManagers.currentConfig()
            if (config.apiKey.isNullOrBlank()) {
                withContext(Dispatchers.Main) { listener.onError("未配置 AI API Key，无法开始处理。") }
                return@withContext
            }

            for (task in tasks) {
                val sourceDirForNaming = sourceInfo.downloadDir?.toFile() ?: File(sourceInfo.dirname ?: "unknown")
                val targetDir = createTargetDir(sourceDirForNaming, sourceInfo.findBaseInfo(), task)
                if (targetDir == null) {
                    withContext(Dispatchers.Main) { listener.onError("无法创建目标文件夹") }
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
                            previousDir?.resolve(fileName)?.takeIf(File::exists)?.let { colorFile ->
                                BitmapFactory.decodeFile(colorFile.absolutePath)
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

                    // 修复：使用 val = try-catch 表达式，消除编译警告
                    val resultBitmap = try {
                        val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                        result.getOrThrow()
                    } catch (e: Exception) {
                        Log.e("BatchAi", "Page ${index + 1} failed: ${e.message}", e)
                        if (index == 0 || failCount > 2) {
                            withContext(Dispatchers.Main) {
                                listener.onError("处理失败: ${e.message ?: "未知错误"}")
                            }
                            targetDir.deleteRecursively()
                            return@withContext
                        }
                        failCount++
                        sourceBitmap
                    }

                    saveBitmap(targetDir, fileName, resultBitmap)

                    if (resultBitmap != sourceBitmap) {
                        delay(1000)
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
        sourceDir: File,
        sourceInfo: BaseGalleryInfo,
        task: ProcessTarget,
    ): File? {
        val suffix = when (task) {
            ProcessTarget.ColorOnly -> "[AI-Color]"
            ProcessTarget.TranslateOnly -> "[AI-TL]"
            ProcessTarget.TranslateFromColor -> "[AI-Color+TL]"
        }
        val newTitle = "$suffix ${sourceInfo.title ?: sourceInfo.gid}"
        val newDirName = FileUtils.sanitizeFilename(newTitle)
        val parent = if (sourceDir.isFile) sourceDir.parentFile else sourceDir.parentFile
        return parent?.resolve(newDirName)?.apply { mkdirs() }
    }

    private fun saveBitmap(targetDir: File, name: String, bitmap: Bitmap) {
        val targetFile = targetDir.resolve(name)
        FileOutputStream(targetFile).use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
        }
    }

    private suspend fun registerAsDownload(
        targetDir: File,
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
            title = targetDir.name,
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
        val pageCount = targetDir.listFiles()?.size ?: 0
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