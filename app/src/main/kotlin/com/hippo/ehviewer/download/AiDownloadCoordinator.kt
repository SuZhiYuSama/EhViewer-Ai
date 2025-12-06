package com.hippo.ehviewer.download

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.AiManagers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import splitties.init.appCtx

object AiDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingTasks: MutableMap<Long, AiProcessMode> = mutableMapOf()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 创建一个无限容量的通道作为任务队列，确保串行执行
    private val taskChannel = Channel<Pair<DownloadInfo, AiProcessMode>>(Channel.UNLIMITED)

    private const val NOTIFICATION_CHANNEL_ID = "ai_processing"
    private const val NOTIFICATION_ID_BASE = 10000

    init {
        restoreTasks()
        createNotificationChannel()
        // 启动消费者协程，它会一直运行，等待通道里的新任务，并按顺序一个接一个处理
        startTaskConsumer()
    }

    private fun startTaskConsumer() {
        scope.launch {
            for ((info, mode) in taskChannel) {
                try {
                    Log.i("AiDownload", "Processing task from queue: GID=${info.gid}")
                    processSingleTask(info, mode)
                } catch (e: Exception) {
                    Log.e("AiDownload", "Task execution failed for GID=${info.gid}", e)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = NotificationManagerCompat.from(appCtx)
        val channel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        ).setName("AI Processing")
            .setDescription("AI 图像处理进度与状态")
            .build()
        manager.createNotificationChannel(channel)
    }

    private fun restoreTasks() {
        Settings.aiPendingTasks.value.forEach { entry ->
            val parts = entry.split(":")
            val gid = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val mode = parts.getOrNull(1)?.let { raw ->
                runCatching { AiProcessMode.valueOf(raw) }.getOrNull()
            } ?: return@forEach
            pendingTasks[gid] = mode
        }
    }

    private fun persistTasks() {
        Settings.aiPendingTasks.value = pendingTasks.map { "${it.key}:${it.value}" }.toSet()
    }

    fun enqueue(galleryInfo: BaseGalleryInfo, mode: AiProcessMode) {
        val config = AiManagers.currentConfig()
        if (config.apiKey.isNullOrBlank()) {
            showErrorNotification(galleryInfo.gid.toInt(), "AI 任务添加失败", "请先在设置中配置 AI API Key")
            return
        }

        pendingTasks[galleryInfo.gid] = mode
        persistTasks()
        Log.i("AiDownload", "Task enqueued via download finish for GID: ${galleryInfo.gid}, Mode: $mode")
        showToast("已加入 AI 处理队列")
    }

    fun onDownloadFinished(info: DownloadInfo) {
        val mode = pendingTasks.remove(info.gid) ?: return
        persistTasks()
        if (info.state != DownloadInfo.STATE_FINISH) return

        startManualProcessing(info, mode)
    }

    fun startManualProcessing(info: DownloadInfo, mode: AiProcessMode) {
        if (mode == AiProcessMode.NONE) return

        val config = AiManagers.currentConfig()
        if (config.apiKey.isNullOrBlank()) {
            showToast("请先配置 AI API Key")
            return
        }

        Log.i("AiDownload", "Queueing AI processing for GID: ${info.gid}")
        showToast("已加入 AI 处理队列...")

        // 将任务发送到通道，而非直接启动协程处理
        // trySend 可以在非挂起函数中使用
        taskChannel.trySend(info to mode)
    }

    private suspend fun processSingleTask(info: DownloadInfo, mode: AiProcessMode) {
        val processor = BatchAiProcessor()
        val notificationId = NOTIFICATION_ID_BASE + (info.gid % 1000).toInt()

        // 初始通知
        showProgressNotification(notificationId, "AI 准备中", "正在解析文件...", 0, 0)

        // 使用 suspendCoroutine 或者简单的回调包装来保持当前协程挂起，直到任务完成
        // 这里 BatchAiProcessor.processGallery 是 suspend 函数，所以直接调用即可等待它完成
        processor.processGallery(info, mode, object : BatchAiProcessor.ProgressListener {
            override fun onProgress(current: Int, total: Int, message: String) {
                Log.d("AiDownload", "[$current/$total] $message")
                showProgressNotification(notificationId, "AI 处理中: ${info.label ?: info.gid}", message, current, total)
            }

            override fun onComplete(newInfo: List<DownloadInfo>) {
                Log.d("AiDownload", "AI processing completed for ${info.gid}")
                showCompleteNotification(notificationId, "AI 处理完成", "已生成 ${newInfo.size} 个新版本")
            }

            override fun onError(error: String) {
                Log.e("AiDownload", "AI processing failed: $error")
                showErrorNotification(notificationId, "AI 处理失败", error)
            }
        })
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProgressNotification(id: Int, title: String, content: String, current: Int, total: Int) {
        if (!checkPermission()) return
        val builder = NotificationCompat.Builder(appCtx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        NotificationManagerCompat.from(appCtx).notify(id, builder.build())
    }

    private fun showCompleteNotification(id: Int, title: String, content: String) {
        if (!checkPermission()) {
            showToast("$title: $content")
            return
        }
        val intent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(appCtx, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(appCtx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setProgress(0, 0, false)
            .setAutoCancel(true)

        NotificationManagerCompat.from(appCtx).notify(id, builder.build())
    }

    private fun showErrorNotification(id: Int, title: String, content: String) {
        if (!checkPermission()) {
            Log.w("AiDownload", "无通知权限，使用 Toast 显示错误: $content")
            showToast("$title: $content")
            return
        }

        val intent = Intent(appCtx, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(appCtx, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(appCtx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        NotificationManagerCompat.from(appCtx).notify(id, builder.build())
    }

    private fun checkPermission(): Boolean {
        val granted = ActivityCompat.checkSelfPermission(
            appCtx,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w("AiDownload", "缺少 POST_NOTIFICATIONS 权限，无法发送通知")
        }
        return granted
    }
}