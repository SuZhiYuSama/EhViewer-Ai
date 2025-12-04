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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class BatchAiProcessor {

    interface ProgressListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onComplete(newInfo: List<DownloadInfo>)
        fun onError(error: String)
    }

    suspend fun processGallery(
        sourceInfo: DownloadInfo,
        mode: AiProcessMode,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val sourceDir = sourceInfo.downloadDir?.toFile()

        if (sourceDir == null || !sourceDir.exists()) {
            withContext(Dispatchers.Main) { listener.onError("找不到原始下载文件") }
            return@withContext
        }

        val imageFiles = sourceDir.listFiles { file ->
            val name = file.name.lowercase()
            name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")
        }?.sortedBy(File::getName) ?: emptyList()

        if (imageFiles.isEmpty()) {
            withContext(Dispatchers.Main) { listener.onError("文件夹内无图片") }
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

        // 检查配置
        val config = AiManagers.currentConfig()
        if (config.apiKey.isNullOrBlank()) {
            withContext(Dispatchers.Main) { listener.onError("未配置 AI API Key，无法开始处理。") }
            return@withContext
        }

        for (task in tasks) {
            val targetDir = createTargetDir(sourceDir, sourceInfo.findBaseInfo(), task)
            if (targetDir == null) {
                withContext(Dispatchers.Main) { listener.onError("无法创建目标文件夹") }
                return@withContext
            }

            val total = imageFiles.size
            var failCount = 0

            for ((index, file) in imageFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    listener.onProgress(index + 1, total, "正在处理第 ${index + 1} 页... (任务: ${task.desc})")
                }

                val fileName = file.name

                val sourceBitmap = when (task) {
                    ProcessTarget.TranslateFromColor -> {
                        previousDir?.resolve(fileName)?.takeIf(File::exists)?.let { colorFile ->
                            BitmapFactory.decodeFile(colorFile.absolutePath)
                        }
                    }
                    else -> BitmapFactory.decodeFile(file.absolutePath)
                }

                if (sourceBitmap == null) {
                    Log.e("BatchAi", "Failed to decode bitmap: ${file.absolutePath}")
                    continue
                }

                val prompt = if (task == ProcessTarget.ColorOnly) {
                    GeminiManager.PROMPT_COLORIZE
                } else {
                    GeminiManager.PROMPT_TRANSLATE
                }

                // 核心修改：使用 try-catch 表达式直接赋值，移除 var 和后续的 null 判断
                val resultBitmap = try {
                    val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                    result.getOrThrow()
                } catch (e: Exception) {
                    Log.e("BatchAi", "Page ${index + 1} failed: ${e.message}", e)
                    // 如果第一页就失败，或者连续失败，大概率是配置问题，直接中止
                    if (index == 0 || failCount > 2) {
                        withContext(Dispatchers.Main) {
                            listener.onError("处理失败: ${e.message ?: "未知错误"}")
                        }
                        // 清理垃圾文件
                        targetDir.deleteRecursively()
                        return@withContext
                    }
                    failCount++
                    // 单张失败可以使用原图（可选），这里为了强提醒，我们跳过保存，或者保存原图但计数
                    // 选择保存原图以保持页码一致性
                    sourceBitmap
                }

                // 此时 resultBitmap 必定不为 null，直接调用保存
                saveBitmap(targetDir, fileName, resultBitmap)

                // 避免 API 速率限制
                delay(1000)
            }

            val downloadInfo = registerAsDownload(targetDir, sourceInfo.findBaseInfo(), task)
            if (downloadInfo != null) {
                results += downloadInfo
            }
            previousDir = targetDir
        }

        withContext(Dispatchers.Main) { listener.onComplete(results) }
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
        return sourceDir.parentFile?.resolve(newDirName)?.apply { mkdirs() }
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