package com.livemonitor.app.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.io.File

/**
 * SelfTest 夹具：自定义提示音安全实现（内部私有目录、大小限制、固定文件名）。
 */
class SafeSoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val maxBytes = 2 * 1024 * 1024
    private val targetFile = File(appContext.filesDir, "custom_sound.dat")

    fun play() {
        val player = android.media.MediaPlayer()
        player.setDataSource(targetFile.absolutePath)
    }
}

/** SelfTest 夹具：通知渠道迁移到 v2 并清理旧渠道。 */
object SafeNotify {
    const val CHANNEL_ALERTS_V2 = "live_alerts_v2"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_V2, "开播提醒", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.deleteNotificationChannel("live_alerts")
    }
}
