package com.livemonitor.app.core.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * UI -> 前台服务 的唯一控制入口。
 *
 * 所有命令都以显式 Intent 发送给未导出的 [MonitorService]，
 * 不使用隐式广播、不暴露任何 Receiver。
 */
object MonitorControl {
    const val ACTION_START = "com.livemonitor.app.action.START"
    const val ACTION_STOP = "com.livemonitor.app.action.STOP"
    const val ACTION_SET_PAUSED = "com.livemonitor.app.action.SET_PAUSED"
    const val ACTION_WAKE = "com.livemonitor.app.action.WAKE"
    /** 用户点“忽略”：只关闭该条提醒（停止循环提示音），不影响后续监测。 */
    const val ACTION_DISMISS_ALERT = "com.livemonitor.app.action.DISMISS_ALERT"
    /** 用户点“不再提醒”：本次开播静音，主播下播后自动恢复。 */
    const val ACTION_MUTE_ALERT = "com.livemonitor.app.action.MUTE_ALERT"
    /** 提醒通知被用户滑掉 / 自动关闭时，停止循环提示音。 */
    const val ACTION_ALERT_DISMISSED = "com.livemonitor.app.action.ALERT_DISMISSED"

    const val EXTRA_ROOM_KEY = "room_key"
    const val EXTRA_PAUSED = "paused"

    val runtime: StateFlow<MonitorRuntime> get() = MonitorRuntimeHolder.runtime

    fun start(context: Context) = send(context, ACTION_START)

    fun stop(context: Context) = send(context, ACTION_STOP)

    fun setPaused(context: Context, paused: Boolean) {
        send(context, ACTION_SET_PAUSED, buildIntent(context).putExtra(EXTRA_PAUSED, paused))
    }

    fun wake(context: Context) = send(context, ACTION_WAKE)

    fun dismissAlert(context: Context, roomKey: String) = sendAlertAction(context, ACTION_DISMISS_ALERT, roomKey)

    fun muteAlert(context: Context, roomKey: String) = sendAlertAction(context, ACTION_MUTE_ALERT, roomKey)

    private fun sendAlertAction(context: Context, action: String, roomKey: String) {
        val intent = buildIntent(context).setAction(action).putExtra(EXTRA_ROOM_KEY, roomKey)
        try {
            context.startService(intent)
        } catch (_: Throwable) {
            // 服务未运行时忽略（提醒动作只会从已存在的通知里触发）
        }
    }

    private fun send(context: Context, action: String) = send(context, action, buildIntent(context))

    private fun send(context: Context, action: String, intent: Intent) {
        intent.action = action
        try {
            // 只有 START 需要以前台服务方式启动；其它命令走普通 startService，
            // 避免“已停止状态下点忽略通知”触发 5 秒内必须 startForeground 的崩溃路径。
            if (action == ACTION_START) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Throwable) {
            // 服务已停止或后台启动受限：命令直接忽略即可，UI 会重新启动服务。
        }
    }

    private fun buildIntent(context: Context): Intent =
        Intent(context, MonitorService::class.java)
}
