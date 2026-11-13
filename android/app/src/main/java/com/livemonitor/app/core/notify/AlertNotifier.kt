package com.livemonitor.app.core.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.livemonitor.app.R
import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.util.AppLog

/**
 * 提醒的运行时管理（Android 薄封装，核心判定都在纯逻辑 [AlertCenter]/[AlertPolicy] 里）。
 *
 * 职责：
 * - 发提醒通知（最多 4 条，超出取消最旧）、维护“不再提醒”mute 生命周期；
 * - 首声：通知渠道静音，由 [AlertSoundPlayer] 播放内置或自定义提示音；
 * - 循环提示音：同一播放器 + 同一音源，首声后 `loopSoundSec + 3s` 才开始，避免叠音；
 * - `popupAutoCloseSec > 0` 时用 Handler 精确到点自动取消；
 * - 用户点“忽略/不再提醒”/滑掉通知/清空/服务停止/暂停时停止循环音。
 *
 * 线程模型：Service 的协程（IO 线程）与主线程 Handler 回调都会访问本类，
 * 所有状态修改都在 [lock] 内完成；声音播放统一投递到主线程执行。
 */
class AlertNotifier(context: Context) {

    private val appContext: Context = context.applicationContext
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val center = AlertCenter()
    private val loopPlayer = LoopSoundPlayer(appContext)
    private val autoCloseCallbacks = HashMap<String, Runnable>()
    private val lock = Any()

    @Volatile
    private var released = false

    @Volatile
    private var soundEnabled = true

    /** 当前自定义提示音文件名（空 = 内置提示音）；首声与循环音共用。 */
    @Volatile
    private var customSoundFile = ""

    /** 服务是否处于暂停：暂停时不再播放任何循环音。 */
    @Volatile
    private var paused = false

    private var loopActive = false
    private var loopIntervalMs = 0L

    private val loopRunnable: Runnable = object : Runnable {
        override fun run() {
            if (released) return
            var reschedule = false
            synchronized(lock) {
                if (!loopActive) return
                // 通知可能已经被用户点开（autoCancel）或清空，先做一次消失检测再决定是否响铃。
                sweepLocked()
                val desiredSec = desiredLoopSecLocked()
                if (desiredSec <= 0) {
                    stopLoopLocked()
                    return
                }
                loopIntervalMs = AlertPolicy.loopIntervalMillis(desiredSec)
                loopPlayer.updateSource(customSoundFile, soundEnabled)
                loopPlayer.playChime()
                reschedule = true
            }
            if (reschedule) handler.postDelayed(this, loopIntervalMs)
        }
    }

    /** 是否处于“不再提醒”状态（本次开播内有效）。 */
    fun isMuted(roomKey: String): Boolean = synchronized(lock) { center.isMuted(roomKey) }

    /**
     * 展示一条开播提醒。
     * 调用方已经完成 becameLive/冷却/notifyAlreadyLive 判定。
     *
     * @return 通知是否成功发出（用于自动打开直播间等后续动作）。
     */
    fun showLiveAlert(room: RoomItem, result: CheckResult, settings: AppSettings): Boolean {
        if (released) return false
        val text = AlertPolicy.buildAlertText(
            displayName = room.displayName,
            owner = result.owner,
            platformName = room.platform.displayName,
            area = result.area,
            sub = result.sub,
            title = result.title,
            fallbackTitle = appContext.getString(R.string.notify_alert_live),
        )
        var activeCount = 0
        var posted = false
        synchronized(lock) {
            if (released) return false
            soundEnabled = settings.soundOn
            customSoundFile = settings.customSoundFile
            // 旧通知可能被用户点开/清空（没有回调），先对齐一次真实状态。
            sweepLocked()
            val plan = center.show(
                roomKey = room.key,
                now = System.currentTimeMillis(),
                loopSoundSec = if (settings.soundOn) settings.loopSoundSec else 0,
                popupAutoCloseSec = settings.popupAutoCloseSec,
            )
            for (evicted in plan.evicted) {
                cancelAutoCloseLocked(evicted.roomKey)
                cancelNotificationLocked(evicted.notificationId)
            }
            val manager = notificationManager
            posted = if (manager == null) {
                false
            } else {
                try {
                    manager.notify(
                        plan.alert.notificationId,
                        NotificationHelper.buildAlertNotification(
                            context = appContext,
                            roomKey = room.key,
                            notificationId = plan.alert.notificationId,
                            text = text,
                        ),
                    )
                    true
                } catch (t: Throwable) {
                    AppLog.error("Notify", "post alert failed", t)
                    false
                }
            }
            scheduleAutoCloseLocked(plan.alert)
            reconcileLoopLocked()
            activeCount = center.activeCount()
        }
        if (posted) {
            // 首声由 App 播放器播放（渠道已静音）；投递到主线程，和循环音共用同一个播放器，
            // 正在播放时 playOnce 会跳过而不是叠加。
            handler.post {
                if (!released) {
                    AlertSoundPlayer.playOnce(appContext, customSoundFile, soundEnabled)
                }
            }
        }
        // 安全：只记录平台 key 与房间 id，不记录主播名/标题/URL。
        AppLog.info("Notify", "alert posted=${posted} platform=${room.platform.key} room=${room.id} alerts=$activeCount")
        return posted
    }

    /** 用户点“忽略” / 滑掉通知 / 自动关闭。 */
    fun dismissAlert(roomKey: String) {
        if (released) return
        synchronized(lock) {
            val removed = center.remove(roomKey)
            cancelAutoCloseLocked(roomKey)
            removed.removed?.let { cancelNotificationLocked(it.notificationId) }
            reconcileLoopLocked()
        }
    }

    /** 用户点“不再提醒”：本次开播静音 + 关闭该条提醒（循环音若还有其他提醒则继续）。 */
    fun muteAlert(roomKey: String) {
        if (released) return
        synchronized(lock) {
            center.mute(roomKey)
            val removed = center.remove(roomKey)
            cancelAutoCloseLocked(roomKey)
            removed.removed?.let { cancelNotificationLocked(it.notificationId) }
            reconcileLoopLocked()
        }
        AppLog.info("Notify", "room muted for current live session")
    }

    /** 可靠的下播结果：解除“不再提醒”（下一次开播照常提醒）。 */
    fun onReliableOffline(roomKey: String) {
        if (released) return
        val changed = synchronized(lock) { center.onReliableOffline(roomKey) }
        if (changed) AppLog.info("Notify", "mute cleared after reliable offline")
    }

    /** 房间被删除：清掉提醒与 mute，避免残留通知。 */
    fun forgetRoom(roomKey: String) {
        if (released) return
        synchronized(lock) {
            val removed = center.forget(roomKey)
            cancelAutoCloseLocked(roomKey)
            removed?.let { cancelNotificationLocked(it.notificationId) }
            reconcileLoopLocked()
        }
    }

    /** 设置变化实时生效（提示音开关/自定义音源影响首声与循环音）。 */
    fun syncSettings(settings: AppSettings) {
        if (released) return
        synchronized(lock) {
            soundEnabled = settings.soundOn
            customSoundFile = settings.customSoundFile
            loopPlayer.updateSource(customSoundFile, soundEnabled)
            reconcileLoopLocked()
        }
    }

    /** 服务暂停/继续：暂停时停止循环音（提醒通知保留），继续后恢复循环。 */
    fun setPaused(value: Boolean) {
        if (released) return
        synchronized(lock) {
            if (paused == value) return
            paused = value
            if (value) {
                stopLoopLocked()
            } else {
                reconcileLoopLocked()
            }
        }
        AppLog.info("Notify", "alert sounds paused=$value")
    }

    /**
     * 对齐“通知栏真实状态”：用户点击通知（autoCancel）/清空全部/个别 ROM 静默移除时，
     * 我们收不到可靠回调，用 getActiveNotifications 检测并清理，顺带停止循环音。
     * 每轮检测结束后、以及循环音每次触发前都会调用（开销很小）。
     */
    fun sweep() {
        if (released) return
        synchronized(lock) {
            sweepLocked()
            reconcileLoopLocked()
        }
    }

    /** 服务停止：取消所有提醒通知、停止循环音（不释放播放器，供 [shutdown] 统一释放）。 */
    fun cancelAll() {
        synchronized(lock) {
            cancelAllLocked()
        }
    }

    /** 服务销毁：取消一切并释放音频资源；之后本对象不可再用。 */
    fun shutdown() {
        synchronized(lock) {
            released = true
            cancelAllLocked()
            handler.removeCallbacksAndMessages(null)
            loopPlayer.release()
        }
    }

    // ---------------- 内部实现 ----------------

    /** 取消全部提醒/循环音/定时器。调用方需持有 [lock]。 */
    private fun cancelAllLocked() {
        stopLoopLocked()
        for (callback in autoCloseCallbacks.values) handler.removeCallbacks(callback)
        autoCloseCallbacks.clear()
        for (alert in center.clear()) cancelNotificationLocked(alert.notificationId)
    }

    /** 移除“通知栏里已经不存在”的提醒（用户点开/清空/ROM 移除）。调用方需持有 [lock]。 */
    private fun sweepLocked() {
        if (center.activeCount() == 0) return
        val manager = notificationManager ?: return
        val activeIds = try {
            manager.activeNotifications.mapTo(HashSet()) { it.id }
        } catch (t: Throwable) {
            AppLog.error("Notify", "query active notifications failed", t)
            return
        }
        for (alert in center.alerts()) {
            if (alert.notificationId !in activeIds) {
                center.remove(alert.roomKey)
                cancelAutoCloseLocked(alert.roomKey)
                AppLog.info("Notify", "alert disappeared, cleaned up")
            }
        }
    }

    private fun scheduleAutoCloseLocked(alert: AlertCenter.Alert) {
        cancelAutoCloseLocked(alert.roomKey)
        val closeAt = alert.autoCloseAt ?: return
        val notificationId = alert.notificationId
        val roomKey = alert.roomKey
        val callback = Runnable {
            if (released) return@Runnable
            synchronized(lock) {
                val removed = center.remove(roomKey)
                autoCloseCallbacks.remove(roomKey)
                if (removed.removed != null) {
                    cancelNotificationLocked(notificationId)
                    AppLog.info("Notify", "alert auto closed")
                }
                reconcileLoopLocked()
            }
        }
        autoCloseCallbacks[roomKey] = callback
        handler.postDelayed(callback, (closeAt - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelAutoCloseLocked(roomKey: String) {
        autoCloseCallbacks.remove(roomKey)?.let { handler.removeCallbacks(it) }
    }

    private fun cancelNotificationLocked(notificationId: Int) {
        try {
            notificationManager?.cancel(notificationId)
        } catch (t: Throwable) {
            AppLog.error("Notify", "cancel notification failed", t)
        }
    }

    /** 当前有效的循环间隔（秒）；暂停/提示音关闭/没有提醒时返回 0。调用方需持有 [lock]。 */
    private fun desiredLoopSecLocked(): Int {
        if (paused || !soundEnabled) return 0
        return center.desiredLoopSec()
    }

    /** 让循环音与当前提醒集合/设置保持一致（启动、换间隔、或全部关闭）。调用方需持有 [lock]。 */
    private fun reconcileLoopLocked() {
        val desiredSec = desiredLoopSecLocked()
        if (desiredSec <= 0) {
            stopLoopLocked()
            return
        }
        val desiredMs = AlertPolicy.loopIntervalMillis(desiredSec)
        if (loopActive && loopIntervalMs == desiredMs) return
        stopLoopLocked()
        loopActive = true
        loopIntervalMs = desiredMs
        loopPlayer.updateSource(customSoundFile, soundEnabled)
        // 首声（App 播放器）刚响过：第一次循环额外 +3s，循环音与首声共用播放器不会叠音。
        handler.postDelayed(loopRunnable, AlertPolicy.firstLoopDelayMillis(desiredSec))
    }

    private fun stopLoopLocked() {
        loopActive = false
        handler.removeCallbacks(loopRunnable)
        loopPlayer.stop()
    }
}
