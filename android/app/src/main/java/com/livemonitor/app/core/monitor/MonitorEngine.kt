package com.livemonitor.app.core.monitor

import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.model.RoomStatusKeys
import com.livemonitor.app.core.model.isReliable
import com.livemonitor.app.core.platform.PlatformApi

/**
 * 检测结果状态机（从桌面版 RoomMonitor 移植并加固）。
 *
 * 规则（与桌面版一致，核心目标是“不误报、不打扰”）：
 * 1. 可靠结果（[LiveState.LIVE] / [LiveState.OFFLINE]）立即提交；
 * 2. 不可靠结果（[LiveState.ERROR] / [LiveState.NOT_FOUND]）需要**连续 2 次**才覆盖
 *    已知状态；第一次出现时保持上次状态，仅标记“网络异常”（transient）；
 * 3. 只有“上次不是直播 → 直播”（Offline→Live）或首次检测即为直播时才触发开播事件；
 * 4. 同一房间两次开播提醒至少间隔 [notifyCooldownSec] 秒（默认 180）；
 * 5. 平台返回的开播时间先经 [PlatformApi.sanitizeLiveStartAt] 过滤（未来/过旧丢弃）。
 *
 * 线程安全：所有公开方法都 `@Synchronized`；由服务单协程调用，但“立即检测”等
 * 手动路径可能来自其它线程，因此状态必须加锁。时钟可注入，便于单元测试。
 */
class MonitorEngine(
    private val notifyCooldownSec: Int = DEFAULT_NOTIFY_COOLDOWN_SEC,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** 服务消费的最终结果。 */
    data class Outcome(
        val result: CheckResult,
        /** 需要持久化到 RoomItem.lastStatus 的状态键。 */
        val persistedStatus: String,
        /** 本次结果是否因为网络异常而保持了上次状态。 */
        val transient: Boolean,
        /** 是否需要向用户弹出开播提醒（已应用冷却与可靠性规则）。 */
        val becameLive: Boolean,
    )

    private data class RoomState(
        var lastState: LiveState = LiveState.UNKNOWN,
        var lastTitle: String = "",
        var everChecked: Boolean = false,
        var pendingState: LiveState = LiveState.UNKNOWN,
        var pendingCount: Int = 0,
        var lastNotifyAt: Long = 0L,
    )

    private val states = HashMap<String, RoomState>()

    /**
     * 处理一次检测结果，返回最终可展示/可持久化的结果。
     *
     * @param room 直播间（用 [RoomItem.key] 区分状态）
     * @param raw 平台检测的原始结果（字段已由 PlatformApi 清洗）
     */
    @Synchronized
    fun process(room: RoomItem, raw: CheckResult): Outcome {
        val state = states.getOrPut(room.key) { RoomState() }

        // 开播时间先做合理性过滤：未来时间/过旧时间/异常正则匹配一律丢弃
        val sanitizedStart = PlatformApi.sanitizeLiveStartAt(raw.liveStartAt, clock())
        val incoming = raw.copy(liveStartAt = sanitizedStart)

        // 记住最近一次非空标题，供网络异常时补全展示
        if (incoming.title.isNotEmpty()) state.lastTitle = incoming.title

        if (!state.everChecked) {
            state.everChecked = true
            state.lastState = incoming.state
            state.pendingState = LiveState.UNKNOWN
            state.pendingCount = 0
            val becameLive = incoming.state == LiveState.LIVE && allowNotify(state)
            return Outcome(
                result = incoming,
                persistedStatus = RoomStatusKeys.of(incoming.state),
                transient = false,
                becameLive = becameLive,
            )
        }

        if (incoming.isReliable) {
            state.pendingState = LiveState.UNKNOWN
            state.pendingCount = 0
            var becameLive = false
            if (incoming.state != state.lastState) {
                // 只在明确的 Offline→Live 时提醒；首次已直播由上面的分支处理
                becameLive = incoming.state == LiveState.LIVE && state.lastState == LiveState.OFFLINE
                state.lastState = incoming.state
            }
            if (becameLive) becameLive = allowNotify(state)
            return Outcome(
                result = incoming,
                persistedStatus = RoomStatusKeys.of(incoming.state),
                transient = false,
                becameLive = becameLive,
            )
        }

        // ---- 不可靠结果（ERROR / NOT_FOUND）：连续 2 次才覆盖 ----
        if (state.pendingState == incoming.state) {
            state.pendingCount++
        } else {
            state.pendingState = incoming.state
            state.pendingCount = 1
        }

        if (state.pendingCount >= 2) {
            state.lastState = incoming.state
            state.pendingState = LiveState.UNKNOWN
            state.pendingCount = 0
            return Outcome(
                result = incoming,
                persistedStatus = RoomStatusKeys.of(incoming.state),
                transient = false,
                becameLive = false,
            )
        }

        // 第一次不可靠：保持上次已知状态，只在界面提示“网络异常”
        val display = incoming.copy(
            state = state.lastState,
            sub = CheckResult.SUB_NETWORK_ANOMALY,
            title = incoming.title.ifEmpty { state.lastTitle },
            // 不可信结果不携带开播时间，避免清掉/污染历史上次开播时间
            liveStartAt = null,
        )
        return Outcome(
            result = display,
            persistedStatus = RoomStatusKeys.of(state.lastState),
            transient = true,
            becameLive = false,
        )
    }

    /** 房间被删除后清掉状态（下次重新添加视为首次检测）。 */
    @Synchronized
    fun removeState(roomKey: String) {
        states.remove(roomKey)
    }

    /** 全量重置（服务停止/退出时调用）。 */
    @Synchronized
    fun reset() {
        states.clear()
    }

    /** 读取最近一次已知状态（未检测过返回 null）；供服务恢复界面使用。 */
    @Synchronized
    fun lastKnownState(roomKey: String): LiveState? =
        states[roomKey]?.takeIf { it.everChecked }?.lastState

    private fun allowNotify(state: RoomState): Boolean {
        val now = clock()
        if (state.lastNotifyAt > 0L) {
            val elapsed = now - state.lastNotifyAt
            // 时钟回拨（elapsed < 0）时按“已过冷却期”处理，避免永不再提醒
            if (elapsed in 0 until notifyCooldownSec * 1000L) return false
        }
        state.lastNotifyAt = now
        return true
    }

    companion object {
        const val DEFAULT_NOTIFY_COOLDOWN_SEC = 180
    }
}
