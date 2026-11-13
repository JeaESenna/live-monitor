package com.livemonitor.app.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.livemonitor.app.R
import com.livemonitor.app.core.jump.RoomJumpIntent
import com.livemonitor.app.core.monitor.MonitorControl
import com.livemonitor.app.core.monitor.MonitorService
import com.livemonitor.app.ui.MainActivity
import com.livemonitor.app.util.AppLog
import com.livemonitor.app.util.TimeFormat

/**
 * 通知渠道与通知构造（无状态薄封装）。
 *
 * 两个渠道（创建幂等；渠道一旦创建，声音/重要性由用户系统设置决定，代码不会也不能覆盖，
 * 因此重复调用 [createChannels] 只更新名称/描述，不会崩溃）：
 * - [CHANNEL_SERVICE]：前台服务常驻通知，IMPORTANCE_LOW、静音、无角标；
 * - [CHANNEL_ALERTS]：开播提醒，IMPORTANCE_HIGH、**静音渠道**（振动 + PRIVATE 锁屏）。
 *   首声与循环提示音都由 App 的 [AlertSoundPlayer] 播放（支持自定义提示音），
 *   因此渠道本身不再设置声音，避免出现“系统渠道音 + App 播放器”双声。
 *
 * v1.1.0 渠道迁移：旧的 `live_alerts`（带渠道声音）在升级后会被删除，改用 `live_alerts_v2`；
 * 删除旧渠道会让旧版本的残留提醒一起消失，属于预期行为。
 *
 * 安全约束：
 * - 所有 PendingIntent 都指向本 App 的显式组件（Activity/Service），
 *   使用 FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT，没有隐式广播 / 导出组件；
 * - 点击通知只打开本 App 的 [MainActivity]；“进入直播间”只打开本 App 的 RoomJumpActivity
 *   （由该 Activity 内的用户操作决定是否跳转平台 App/浏览器），通知本身绝不直接发起外部跳转；
 * - 通知正文只来自本地已清洗的数据，不记录到日志。
 */
object NotificationHelper {
    const val CHANNEL_SERVICE = "monitor_service"

    /**
     * 开播提醒渠道 ID。
     * v1.1.0 起改为静音的 v2 渠道（首声由 App 播放器负责，支持自定义提示音）。
     */
    const val CHANNEL_ALERTS = "live_alerts_v2"

    /** 旧版本的开播提醒渠道（带渠道声音）；升级时删除，避免双声。 */
    const val LEGACY_CHANNEL_ALERTS = "live_alerts"

    /** 前台服务常驻通知 ID（与提醒 ID 段 [AlertPolicy.NOTIFICATION_ID_BASE, +10000) 不冲突）。 */
    const val FOREGROUND_NOTIFICATION_ID = 1001

    private const val RC_FOREGROUND_CONTENT = 101
    private const val RC_FOREGROUND_PAUSE = 102
    private const val RC_FOREGROUND_STOP = 103

    /** 前台服务通知要展示的运行状态。 */
    data class ServiceStatus(
        val paused: Boolean,
        val roomCount: Int,
        val liveCount: Int,
        val lastCheckAt: Long?,
    )

    /**
     * 创建（或更新）两个通知渠道。App 启动和 Service 启动时都会调用，必须幂等。
     * 注意：Android 不允许修改已创建渠道的声音/重要性（用户可能已自定义），这里只
     * 设置名称/描述等可更新字段，并用 try/catch 防御个别 ROM 的异常。
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notify_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notify_channel_service_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            // 静音渠道：声音由 App 播放器统一播放（支持自定义提示音、可精确控制叠加与停止）。
            // 仍保持 IMPORTANCE_HIGH + 振动，保证 Heads-up 与锁屏可见性。
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notify_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notify_channel_alerts_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
            deleteLegacyAlertChannel(manager)
        } catch (t: Throwable) {
            // 渠道创建失败不能让 App/服务崩溃（例如个别 ROM 的渠道数量/参数限制）
            AppLog.error("Notify", "create channels failed", t)
        }
    }

    /**
     * 删除 v1.0 的带声音提醒渠道。
     * 渠道声音无法修改，只能换新 ID；旧渠道留着会让“旧提醒”继续用系统提示音从而与
     * App 播放器双声，因此升级时主动删除（旧通知一并清除）。
     */
    private fun deleteLegacyAlertChannel(manager: NotificationManager) {
        try {
            if (manager.getNotificationChannel(LEGACY_CHANNEL_ALERTS) != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ALERTS)
                AppLog.info("Notify", "legacy alert channel removed")
            }
        } catch (t: Throwable) {
            AppLog.error("Notify", "delete legacy alert channel failed", t)
        }
    }

    /** 前台服务常驻通知：低优先级、静音、常驻，带“暂停/继续”“停止”动作。 */
    fun buildForegroundNotification(context: Context, status: ServiceStatus): Notification {
        val title = context.getString(
            if (status.paused) R.string.notify_service_title_paused else R.string.notify_service_title_running,
        )
        val lastCheck = status.lastCheckAt?.let { TimeFormat.lastCheck(it) }
        val text = if (lastCheck == null) {
            context.getString(R.string.notify_service_text_no_check, status.roomCount, status.liveCount)
        } else {
            context.getString(R.string.notify_service_text, status.roomCount, status.liveCount, lastCheck)
        }
        val pauseIntent = servicePendingIntent(
            context = context,
            requestCode = RC_FOREGROUND_PAUSE,
            action = MonitorControl.ACTION_SET_PAUSED,
            paused = !status.paused,
        )
        val stopIntent = servicePendingIntent(
            context = context,
            requestCode = RC_FOREGROUND_STOP,
            action = MonitorControl.ACTION_STOP,
        )
        val pauseLabel = context.getString(
            if (status.paused) R.string.notify_action_resume else R.string.notify_action_pause,
        )
        val pauseIcon = if (status.paused) {
            android.R.drawable.ic_media_play
        } else {
            android.R.drawable.ic_media_pause
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(context, RC_FOREGROUND_CONTENT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(pauseIcon, pauseLabel, pauseIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notify_action_stop),
                stopIntent,
            )
            .build()
    }

    /**
     * 开播提醒通知。
     *
     * 提示音：渠道是静音的，首声由 [AlertNotifier] 调用 [AlertSoundPlayer] 播放；
     * 循环提示音在“首声 + loopSoundSec + 3s”后才开始（见 [AlertPolicy.firstLoopDelayMillis]）。
     *
     * 动作：进入直播间（只打开本 App 的 RoomJumpActivity）/ 忽略 / 不再提醒。
     */
    fun buildAlertNotification(
        context: Context,
        roomKey: String,
        notificationId: Int,
        text: AlertPolicy.AlertText,
    ): Notification {
        val enterRoomIntent = roomJumpPendingIntent(
            context = context,
            requestCode = AlertPolicy.alertActivityRequestCode(
                notificationId,
                AlertPolicy.ActivityAction.ENTER_ROOM,
            ),
            roomKey = roomKey,
        )
        val dismissIntent = servicePendingIntent(
            context = context,
            requestCode = AlertPolicy.alertServiceRequestCode(notificationId, AlertPolicy.AlertAction.DISMISS),
            action = MonitorControl.ACTION_DISMISS_ALERT,
            roomKey = roomKey,
        )
        val muteIntent = servicePendingIntent(
            context = context,
            requestCode = AlertPolicy.alertServiceRequestCode(notificationId, AlertPolicy.AlertAction.MUTE),
            action = MonitorControl.ACTION_MUTE_ALERT,
            roomKey = roomKey,
        )
        val deleteIntent = servicePendingIntent(
            context = context,
            requestCode = AlertPolicy.alertServiceRequestCode(notificationId, AlertPolicy.AlertAction.ALERT_DISMISSED),
            action = MonitorControl.ACTION_ALERT_DISMISSED,
            roomKey = roomKey,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(context.getString(R.string.notify_alert_title))
            .setSubText(text.who)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.bigText))
            // 点击只打开本 App 列表，绝不跳转直播间/浏览器。
            .setContentIntent(
                openAppPendingIntent(
                    context,
                    AlertPolicy.alertActivityRequestCode(notificationId, AlertPolicy.ActivityAction.CONTENT),
                ),
            )
            .setDeleteIntent(deleteIntent)
            .addAction(0, context.getString(R.string.notify_alert_enter_room), enterRoomIntent)
            .addAction(0, context.getString(R.string.notify_alert_ignore), dismissIntent)
            .addAction(0, context.getString(R.string.notify_alert_mute), muteIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setLocalOnly(true)
            .build()
    }

    /** 服务不在前台时（例如进程重建后用户点“忽略”），尽力取消已存在的提醒通知。 */
    fun cancelAlertNotification(context: Context, roomKey: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.cancel(AlertPolicy.notificationId(roomKey))
        } catch (t: Throwable) {
            AppLog.error("Notify", "cancel alert failed", t)
        }
    }

    private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(context, requestCode, intent, pendingFlags())
    }

    /**
     * “进入直播间”只打开本 App 的 RoomJumpActivity（不直接跳外部）：
     * Intent 只携带本地房间 key，不携带任何 URL；用户在该 Activity 里看到 App/浏览器选择。
     *
     * 极端情况（RoomJumpActivity 因安装包被裁剪而不存在）退化为“打开 App 列表”，
     * 保证提醒通知本身仍能正常发出。
     */
    private fun roomJumpPendingIntent(context: Context, requestCode: Int, roomKey: String): PendingIntent {
        val intent = try {
            RoomJumpIntent.create(context, roomKey)
        } catch (t: Throwable) {
            AppLog.error("Notify", "room jump intent unavailable", t)
            return openAppPendingIntent(context, requestCode)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(context, requestCode, intent, pendingFlags())
    }

    private fun servicePendingIntent(
        context: Context,
        requestCode: Int,
        action: String,
        roomKey: String? = null,
        paused: Boolean? = null,
    ): PendingIntent {
        val intent = Intent(context, MonitorService::class.java).setAction(action)
        if (roomKey != null) intent.putExtra(MonitorControl.EXTRA_ROOM_KEY, roomKey)
        if (paused != null) intent.putExtra(MonitorControl.EXTRA_PAUSED, paused)
        return PendingIntent.getService(context, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
