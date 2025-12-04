package com.hippo.ehviewer.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
import kotlinx.coroutines.launch
import splitties.init.appCtx

object AiDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingTasks: MutableMap<Long, AiProcessMode> = mutableMapOf()

    private const val NOTIFICATION_CHANNEL_ID = "ai_processing"
    private const val NOTIFICATION_ID_BASE = 10000

    init {
        restoreTasks()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = NotificationManagerCompat.from(appCtx)
        val channel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW // 进度通知使用 LOW 避免声音干扰
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
        // 在加入任务前进行简单的预检查，如果失败直接弹通知提醒
        val config = AiManagers.currentConfig()
        if (config.apiKey.isNullOrBlank()) {
            showErrorNotification(galleryInfo.gid.toInt(), "AI 任务添加失败", "请先在设置中配置 AI API Key")
            return
        }

        pendingTasks[galleryInfo.gid] = mode
        persistTasks()
        Log.i("AiDownload", "Task enqueued for GID: ${galleryInfo.gid}, Mode: $mode")
    }

    fun onDownloadFinished(info: DownloadInfo) {
        val mode = pendingTasks.remove(info.gid) ?: return
        persistTasks()
        if (info.state != DownloadInfo.STATE_FINISH) return

        Log.i("AiDownload", "Starting AI processing for GID: ${info.gid}")

        scope.launch {
            val processor = BatchAiProcessor()
            val notificationId = NOTIFICATION_ID_BASE + (info.gid % 1000).toInt()

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
    }

    private fun showProgressNotification(id: Int, title: String, content: String, current: Int, total: Int) {
        if (!checkPermission()) return
        val builder = NotificationCompat.Builder(appCtx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload) // 使用系统上传图标作为临时图标
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        NotificationManagerCompat.from(appCtx).notify(id, builder.build())
    }

    private fun showCompleteNotification(id: Int, title: String, content: String) {
        if (!checkPermission()) return
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
        if (!checkPermission()) return

        // 点击通知跳转到主界面，理想情况下应跳转到 AI 设置页，但这里简化跳转到主页
        val intent = Intent(appCtx, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(appCtx, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(appCtx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 错误通知高优先级

        NotificationManagerCompat.from(appCtx).notify(id, builder.build())
    }

    private fun checkPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            appCtx,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}