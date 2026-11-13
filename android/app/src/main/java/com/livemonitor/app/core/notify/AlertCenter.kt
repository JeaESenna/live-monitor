package com.livemonitor.app.core.notify

import com.livemonitor.app.core.model.AppSettings

/**
 * 提醒生命周期状态机（纯逻辑，JVM 可测）。
 *
 * 负责：
 * - 同时最多 [maxAlerts] 条提醒，超出时淘汰最旧的一条（桌面版弹窗上限一致）；
 * - 同一房间重复提醒时复用原通知 ID（避免旧通知残留在通知栏）并移到“最新”位置；
 * - “不再提醒”（mute）只在**本次开播**内生效：可靠的下播结果（OFFLINE，非瞬时网络异常）
 *   到来时自动解除，与桌面版 `_mutedKeys` 语义一致；
 * - 循环提示音的目标间隔：所有活跃提醒里最大的正值（最温和的一个），0 = 不需要循环。
 *
 * 注意：mutedKeys 只存在内存中，进程被杀后丢失是可接受的（与桌面版一致：
 * “不再提醒”本来就是本次开播的临时状态，App 重启后重新提醒更安全，不会漏播）。
 */
class AlertCenter(private val maxAlerts: Int = AlertPolicy.MAX_ACTIVE_ALERTS) {

    /** 一条正在展示的提醒。 */
    data class Alert(
        val roomKey: String,
        val notificationId: Int,
        val shownAt: Long,
        /** 自动关闭时刻；null = 常驻直到用户操作。 */
        val autoCloseAt: Long?,
        /** 该提醒希望的循环提示间隔（秒）；0 = 不循环。 */
        val loopSoundSec: Int,
    )

    data class ShowResult(
        /** 当前展示的提醒（同一房间重复提醒时是新对象/原通知 ID）。 */
        val alert: Alert,
        /** 因超过上限被淘汰、需要取消通知的最旧提醒。 */
        val evicted: List<Alert>,
    )

    data class RemoveResult(
        val removed: Alert?,
        val remaining: Int,
    )

    private val entries = LinkedHashMap<String, Alert>()
    private val muted = HashSet<String>()

    fun alerts(): List<Alert> = entries.values.toList()

    fun activeCount(): Int = entries.size

    fun mutedKeys(): Set<String> = muted.toSet()

    fun isMuted(roomKey: String): Boolean = roomKey in muted

    fun mute(roomKey: String) {
        muted += roomKey
    }

    /**
     * 可靠的下播结果：解除该房间的“不再提醒”，为下一次开播重新提醒做准备。
     * @return 是否真的解除了一个 mute
     */
    fun onReliableOffline(roomKey: String): Boolean = muted.remove(roomKey)

    /** 当前应使用的循环提示间隔（秒）；0 = 没有需要循环的提醒。 */
    fun desiredLoopSec(): Int = entries.values.maxOfOrNull { it.loopSoundSec } ?: 0

    /**
     * 展示一条提醒。
     * @param loopSoundSec 调用方已按“提示音总开关”归一化（关闭时传 0）
     * @param popupAutoCloseSec 0 = 常驻
     */
    fun show(
        roomKey: String,
        now: Long,
        loopSoundSec: Int,
        popupAutoCloseSec: Int,
    ): ShowResult {
        val loop = loopSoundSec.coerceIn(0, AppSettings.MAX_LOOP_SEC)
        // 同一房间重复提醒：复用原 ID（通知栏里替换而不是新增一条），并刷新为“最新”。
        val existing = entries.remove(roomKey)
        val usedIds = entries.values.mapTo(HashSet()) { it.notificationId }
        val id = existing?.notificationId ?: AlertPolicy.resolveNotificationId(roomKey, usedIds)
        val alert = Alert(
            roomKey = roomKey,
            notificationId = id,
            shownAt = now,
            autoCloseAt = AlertPolicy.autoCloseAt(now, popupAutoCloseSec),
            loopSoundSec = loop,
        )
        entries[roomKey] = alert

        val evicted = ArrayList<Alert>(0)
        while (entries.size > maxAlerts) {
            val oldestKey = entries.keys.first()
            entries.remove(oldestKey)?.let { evicted += it }
        }
        return ShowResult(alert = alert, evicted = evicted)
    }

    /** 用户“忽略” / 滑掉通知 / 自动关闭：只移除提醒，不影响 mute。 */
    fun remove(roomKey: String): RemoveResult {
        val removed = entries.remove(roomKey)
        return RemoveResult(removed = removed, remaining = entries.size)
    }

    /** 房间被用户删除时彻底遗忘（提醒 + mute 一起清理）。 */
    fun forget(roomKey: String): Alert? {
        muted.remove(roomKey)
        return entries.remove(roomKey)
    }

    /** 服务停止/用户彻底关闭监测时清空。 */
    fun clear(): List<Alert> {
        val all = entries.values.toList()
        entries.clear()
        return all
    }

    /** 已经到自动关闭时刻的提醒（纯判定；Android 侧用 Handler 精确调度）。 */
    fun dueForAutoClose(now: Long): List<Alert> =
        entries.values.filter { alert -> alert.autoCloseAt != null && alert.autoCloseAt <= now }
}
