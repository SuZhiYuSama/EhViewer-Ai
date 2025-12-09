package com.hippo.ehviewer.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.system.ErrnoException
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
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.ZipFile
import javax.net.ssl.SSLException
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

        // 过滤掉隐藏文件和非图片文件
        private val files = docFile.listFiles().filter { file ->
            val name = file.name?.lowercase().orEmpty()
            !name.startsWith(".") && (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp"))
        }.sortedBy { it.name }

        override val size: Int = files.size
        override fun getName(index: Int) = files.getOrNull(index)?.name ?: "image_$index.jpg"

        override fun getBitmap(index: Int): Bitmap? {
            val file = files.getOrNull(index) ?: return null
            return try {
                context.contentResolver.openInputStream(file.uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) { null }
        }

        override fun copyRawTo(index: Int, outputStream: OutputStream) {
            val file = files.getOrNull(index) ?: return
            context.contentResolver.openInputStream(file.uri)?.use { it.copyTo(outputStream) }
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
                        !name.startsWith(".") && (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp"))
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
            withContext(Dispatchers.Main) { listener.onError("【读取错误】源文件无法读取\n详情: ${e.message}") }
            return@withContext
        }

        if (sourceProvider == null || sourceProvider.size == 0) {
            withContext(Dispatchers.Main) { listener.onError("【读取错误】找不到源图片\n请确认画廊已下载完成且包含图片。") }
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
                withContext(Dispatchers.Main) { listener.onError("【配置错误】未设置 API Key\n请前往 设置 -> AI 填写密钥。") }
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
                    withContext(Dispatchers.Main) { listener.onError("【存储错误】无法创建目标目录\n请检查存储权限或剩余空间。") }
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
                        Log.e("BatchAi", "Cannot read bitmap $fileName, skipping.")
                        continue
                    }

                    val prompt = if (task == ProcessTarget.ColorOnly) GeminiManager.PROMPT_COLORIZE else GeminiManager.PROMPT_TRANSLATE

                    val processedBitmap = try {
                        // 【核心修改】将 API 请求与文件保存放入同一 Try 块，以便统一捕获
                        val result = AiManagers.processBitmap(sourceBitmap, prompt, config)
                        val bitmap = result.getOrThrow()

                        // 立即保存结果，任何IO错误也会被捕获
                        saveBitmapToUri(targetDir, fileName, bitmap)
                        saveProgress(targetDir, index + 1)

                        bitmap // 返回成功处理后的图片
                    } catch (e: Throwable) {
                        Log.e("BatchAi", "Page $index error", e)

                        // 【错误诊断】
                        val errorReport = diagnoseError(e, fileName)

                        // 如果是严重的网络不可达错误，直接中断
                        if (errorReport.isFatal) {
                            withContext(Dispatchers.Main) {
                                listener.onError(errorReport.message)
                            }
                            return@withContext
                        }

                        // 普通错误（如单张图片解码失败或瞬时网络抖动），尝试重试
                        if (failCount > 4) {
                            withContext(Dispatchers.Main) {
                                listener.onError("【任务中断】连续失败多次\n最后一次错误: ${errorReport.title}\n${errorReport.detail}")
                            }
                            return@withContext
                        }
                        failCount++
                        sourceBitmap // 失败回退到原图
                    }

                    if (processedBitmap != sourceBitmap) {
                        delay(1000)
                        failCount = 0
                    }
                }

                try { targetDir.findFile(".ai_progress")?.delete() } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) { listener.onComplete(results) }
        }
    }

    // 【新增】错误诊断类
    private data class ErrorReport(val title: String, val detail: String, val isFatal: Boolean) {
        val message: String get() = "$title\n$detail"
    }

    // 【新增】详细的错误诊断逻辑
    private fun diagnoseError(e: Throwable, fileName: String): ErrorReport {
        val msg = e.message ?: "Unknown Error"
        val cause = e.cause

        // 1. 网络连接类错误 (Network is Unreachable 通常在这里)
        if (e is SocketException || msg.contains("Unreachable", ignoreCase = true) || msg.contains("EHOSTUNREACH")) {
            return ErrorReport(
                "【网络通信异常】",
                "系统提示 'Network is unreachable'。\n原因：应用无法连接到 AI 服务器。\n建议：\n1. 请检查您的 VPN/代理 是否开启。\n2. 确保 EhViewer 已加入分应用代理名单。\n3. 尝试切换代理节点。",
                true
            )
        }
        if (e is UnknownHostException) {
            return ErrorReport(
                "【网络 DNS 错误】",
                "无法解析服务器地址。\n建议：检查网络连接或 DNS 设置。",
                true
            )
        }
        if (e is SocketTimeoutException || e is InterruptedIOException) {
            return ErrorReport(
                "【网络请求超时】",
                "AI 服务器响应时间过长。\n建议：网络状况不佳，请尝试切换节点或稍后重试。",
                false
            )
        }
        if (e is SSLException) {
            return ErrorReport(
                "【SSL 安全连接失败】",
                "无法建立安全连接。\n建议：检查代理设置或系统时间。",
                true
            )
        }

        // 2. API 业务类错误
        if (msg.contains("401")) {
            return ErrorReport(
                "【API 认证失败 (401)】",
                "API Key 无效或已过期。\n建议：检查设置中的 API Key。",
                true
            )
        }
        if (msg.contains("429")) {
            return ErrorReport(
                "【API 配额超限 (429)】",
                "请求过于频繁或配额已用尽。\n建议：稍后重试或检查账户余额。",
                false
            )
        }
        if (msg.contains("400") || msg.contains("INVALID_ARGUMENT")) {
            return ErrorReport(
                "【API 参数错误 (400)】",
                "模型不支持该图片或参数。\n建议：检查是否选择了正确的模型名称。",
                false
            )
        }

        // 3. 文件存储类错误
        if (e is IOException) {
            if (msg.contains("EPERM") || msg.contains("EACCES")) {
                return ErrorReport(
                    "【存储权限被拒绝】",
                    "无法写入文件: $fileName。\n建议：请检查应用的文件存储权限。",
                    true
                )
            }
            if (msg.contains("EROFS")) {
                return ErrorReport(
                    "【存储只读错误】",
                    "文件系统为只读状态。\n建议：检查存储卡状态。",
                    true
                )
            }
            if (msg.contains("ENOSPC")) {
                return ErrorReport(
                    "【存储空间不足】",
                    "剩余空间不足以保存图片。\n建议：清理手机存储空间。",
                    true
                )
            }
        }

        // 4. 其他错误
        return ErrorReport(
            "【未分类异常】",
            "发生了未预期的错误: $msg\n位置: $fileName",
            false
        )
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

        val rawTitle = sourceInfo.title ?: sourceInfo.gid.toString()
        val cleanTitle = rawTitle.replace(Regex("[\\x00-\\x1F\\x7F]+"), " ").trim().take(64)
        val newTitle = "$suffix $cleanTitle"
        val safeDirName = FileUtils.sanitizeFilename(newTitle)

        val downloadDirUri = downloadLocation.toUri()
        val rootDoc = DocumentFile.fromTreeUri(appCtx, downloadDirUri)
            ?: DocumentFile.fromFile(File(downloadDirUri.path ?: Environment.getExternalStorageDirectory().path))

        var targetDir = rootDoc.findFile(safeDirName)

        if (targetDir == null) {
            val searchKey = "${sourceInfo.gid}"
            val existing = rootDoc.listFiles().find {
                it.isDirectory && (it.name?.contains(suffix) == true) && (it.name?.contains(searchKey) == true)
            }
            if (existing != null) {
                val existingName = existing.name ?: ""
                if (existingName.contains("\n") || existingName.contains("\r")) {
                    Log.w("BatchAi", "Ignoring dirty directory: $existingName")
                } else {
                    Log.i("BatchAi", "Resuming from existing directory: $existingName")
                    targetDir = existing
                }
            }
        }

        val isResume = targetDir != null && (targetDir.listFiles().isNotEmpty())

        if (targetDir == null) {
            targetDir = rootDoc.createDirectory(safeDirName) ?: return null
        }

        if (!isResume) {
            Log.i("BatchAi", "Initializing target dir: ${targetDir.name}")
            if (task == ProcessTarget.TranslateFromColor && previousResult != null) {
                val prevUri = previousResult.downloadDir?.toUri()
                if (prevUri != null) {
                    val prevDoc = DocumentFile.fromTreeUri(appCtx, prevUri)
                        ?: DocumentFile.fromFile(File(prevUri.path ?: ""))

                    prevDoc.listFiles().forEach { f ->
                        if (f.name?.endsWith(".ai_progress") == false && f.isFile) {
                            copyFile(f, targetDir!!)
                        }
                    }
                }
            } else if (initialProvider != null) {
                for (i in 0 until initialProvider.size) {
                    val rawName = initialProvider.getName(i)
                    // 强制清洗文件名，防止任何可能的非法字符
                    val safeName = FileUtils.sanitizeFilename(rawName).let {
                        if (it.isBlank()) "image_$i.jpg" else it
                    }

                    val destFile = targetDir.createFile("image/jpeg", safeName)
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
        val rawName = src.name ?: "temp"
        val safeName = FileUtils.sanitizeFilename(rawName)

        val dest = destDir.createFile(src.type ?: "application/octet-stream", safeName) ?: return
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
        // 使用清洗后的文件名查找或创建，确保一致性
        val safeName = FileUtils.sanitizeFilename(name).let { if (it.isBlank()) "processed_image.jpg" else it }
        val file = dir.findFile(safeName) ?: dir.createFile("image/jpeg", safeName) ?: throw IOException("无法创建文件: $safeName")

        appCtx.contentResolver.openOutputStream(file.uri, "wt")?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        } ?: throw IOException("无法打开文件输出流: $safeName")
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