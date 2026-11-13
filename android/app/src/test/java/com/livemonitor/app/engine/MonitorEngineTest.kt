package com.livemonitor.app.engine

import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.model.RoomStatusKeys
import com.livemonitor.app.core.monitor.MonitorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开播状态机的离线单元测试（可注入时钟，无网络）。
 *
 * 覆盖桌面版 RoomMonitor 的核心规则：
 * - 可靠结果（LIVE/OFFLINE）立即提交；
 * - ERROR/NOT_FOUND 连续 2 次才覆盖已知状态，第一次只标记“网络异常”；
 * - 仅 Offline→Live 或首次 Live 才提醒；
 * - 同一房间 180s 冷却；
 * - transient 时保持上次状态、补全标题、丢弃不可信开播时间。
 */
class MonitorEngineTest {

    private var nowMillis: Long = 1_800_000_000_000L

    private fun newEngine(cooldownSec: Int = MonitorEngine.DEFAULT_NOTIFY_COOLDOWN_SEC): MonitorEngine =
        MonitorEngine(notifyCooldownSec = cooldownSec, clock = { nowMillis })

    private fun room(id: String = "5038"): RoomItem = RoomItem(
        platform = Platform.BILIBILI,
        id = id,
        url = "https://live.bilibili.com/$id",
        addedAt = 0L,
    )

    private fun live(title: String = "t", startAt: Long? = null): CheckResult =
        CheckResult(state = LiveState.LIVE, title = title, owner = "n", liveStartAt = startAt)

    private fun offline(): CheckResult = CheckResult(state = LiveState.OFFLINE)

    private fun error(): CheckResult =
        CheckResult(state = LiveState.ERROR, error = "网络错误", failure = FailureKind.NETWORK)

    private fun notFound(): CheckResult =
        CheckResult(state = LiveState.NOT_FOUND, error = "房间不存在", failure = FailureKind.HTTP_STATUS)

    // ---------------- 首次检测 ----------------

    @Test
    fun firstLiveNotifies() {
        val engine = newEngine()
        val outcome = engine.process(room(), live())
        assertTrue(outcome.becameLive)
        assertFalse(outcome.transient)
        assertEquals(LiveState.LIVE, outcome.result.state)
        assertEquals(RoomStatusKeys.LIVE, outcome.persistedStatus)
    }

    @Test
    fun firstOfflineDoesNotNotify() {
        val engine = newEngine()
        val outcome = engine.process(room(), offline())
        assertFalse(outcome.becameLive)
        assertFalse(outcome.transient)
        assertEquals(RoomStatusKeys.OFFLINE, outcome.persistedStatus)
    }

    @Test
    fun firstErrorIsCommittedAsFirstKnownState() {
        // 桌面版语义：第一次结果就是 ERROR 时，直接作为首个已知状态（不做 transient 处理）
        val engine = newEngine()
        val outcome = engine.process(room(), error())
        assertFalse(outcome.becameLive)
        assertEquals(LiveState.ERROR, outcome.result.state)
        assertEquals(RoomStatusKeys.ERROR, outcome.persistedStatus)
        assertFalse(outcome.transient)
    }

    @Test
    fun firstUnknownIsCommitted() {
        val engine = newEngine()
        val outcome = engine.process(
            room(),
            CheckResult(state = LiveState.UNKNOWN, error = "接口受限", failure = FailureKind.UNKNOWN),
        )
        assertEquals(LiveState.UNKNOWN, outcome.result.state)
        assertEquals(RoomStatusKeys.UNKNOWN, outcome.persistedStatus)
        assertFalse(outcome.becameLive)
    }

    // ---------------- 可靠结果 ----------------

    @Test
    fun livePersistsWithoutRepeatedNotify() {
        val engine = newEngine()
        assertTrue(engine.process(room(), live()).becameLive)
        val second = engine.process(room(), live())
        assertFalse(second.becameLive)
        assertEquals(LiveState.LIVE, second.result.state)
        assertFalse(second.transient)
    }

    @Test
    fun offlineToLiveNotifies() {
        val engine = newEngine(cooldownSec = 0)
        engine.process(room(), offline())
        val outcome = engine.process(room(), live())
        assertTrue(outcome.becameLive)
        assertEquals(LiveState.LIVE, outcome.result.state)
        assertEquals(RoomStatusKeys.LIVE, outcome.persistedStatus)
    }

    @Test
    fun offlineToLiveWaitsForCooldownExpiry() {
        val engine = newEngine()
        // 首次即直播 → 提醒一次并记录时间
        assertTrue(engine.process(room(), live()).becameLive)
        // 下播再开播，仍在冷却期内 → 不提醒
        engine.process(room(), offline())
        assertFalse(engine.process(room(), live()).becameLive)
        // 冷却 180s 后（181s）→ 再次提醒
        nowMillis += 181_000L
        engine.process(room(), offline())
        assertTrue(engine.process(room(), live()).becameLive)
    }

    @Test
    fun cooldownSuppressesRapidRepeatedNotify() {
        val engine = newEngine(cooldownSec = 3600)
        assertTrue(engine.process(room(), live()).becameLive)
        engine.process(room(), offline())
        assertFalse(engine.process(room(), live()).becameLive)
    }

    @Test
    fun zeroCooldownAlwaysNotifiesOnOfflineToLive() {
        val engine = newEngine(cooldownSec = 0)
        assertTrue(engine.process(room(), live()).becameLive)
        engine.process(room(), offline())
        assertTrue(engine.process(room(), live()).becameLive)
    }

    @Test
    fun errorToLiveDoesNotNotify() {
        // 只有明确的 Offline→Live 才提醒；从错误状态恢复为直播不重复打扰
        val engine = newEngine(cooldownSec = 0)
        engine.process(room(), error())
        engine.process(room(), error())
        val outcome = engine.process(room(), live())
        assertFalse(outcome.becameLive)
        assertEquals(LiveState.LIVE, outcome.result.state)
    }

    // ---------------- 不可靠结果：连续 2 次确认 ----------------

    @Test
    fun firstErrorKeepsLiveStateAndMarksTransient() {
        val engine = newEngine()
        engine.process(room(), live("标题A"))
        val outcome = engine.process(room(), error())
        assertTrue(outcome.transient)
        assertEquals(LiveState.LIVE, outcome.result.state)
        assertEquals(CheckResult.SUB_NETWORK_ANOMALY, outcome.result.sub)
        assertEquals("标题A", outcome.result.title)
        assertEquals(RoomStatusKeys.LIVE, outcome.persistedStatus)
        assertFalse(outcome.becameLive)
    }

    @Test
    fun secondErrorCommitsErrorState() {
        val engine = newEngine()
        engine.process(room(), live("标题A"))
        engine.process(room(), error())
        val outcome = engine.process(room(), error())
        assertFalse(outcome.transient)
        assertEquals(LiveState.ERROR, outcome.result.state)
        assertEquals(RoomStatusKeys.ERROR, outcome.persistedStatus)
        assertFalse(outcome.becameLive)
    }

    @Test
    fun firstNotFoundKeepsLiveState() {
        val engine = newEngine()
        engine.process(room(), live("标题A"))
        val outcome = engine.process(room(), notFound())
        assertTrue(outcome.transient)
        assertEquals(LiveState.LIVE, outcome.result.state)
        assertEquals(RoomStatusKeys.LIVE, outcome.persistedStatus)
    }

    @Test
    fun secondNotFoundCommitsNotFound() {
        val engine = newEngine()
        engine.process(room(), live())
        engine.process(room(), notFound())
        val outcome = engine.process(room(), notFound())
        assertFalse(outcome.transient)
        assertEquals(LiveState.NOT_FOUND, outcome.result.state)
        assertEquals(RoomStatusKeys.NOT_FOUND, outcome.persistedStatus)
    }

    @Test
    fun consecutiveCountingRequiresSameUnreliableState() {
        // ERROR 与 NOT_FOUND 交替出现不算“连续 2 次同一状态”，不应覆盖已知状态
        val engine = newEngine()
        engine.process(room(), live())
        assertTrue(engine.process(room(), error()).transient)
        assertTrue(engine.process(room(), notFound()).transient)
        assertTrue(engine.process(room(), error()).transient)
        assertEquals(LiveState.LIVE, engine.lastKnownState(room().key))
        // 连续两次同一状态才提交
        val committed = engine.process(room(), error())
        assertFalse(committed.transient)
        assertEquals(LiveState.ERROR, committed.result.state)
    }

    @Test
    fun reliableResultResetsPendingCounter() {
        val engine = newEngine()
        engine.process(room(), live())
        engine.process(room(), error()) // pending=1
        engine.process(room(), live()) // 可靠结果清空 pending
        val outcome = engine.process(room(), error())
        assertTrue(outcome.transient)
        assertEquals(LiveState.LIVE, outcome.result.state)
    }

    @Test
    fun reliableResultAfterErrorCommitsImmediately() {
        val engine = newEngine()
        engine.process(room(), live())
        engine.process(room(), error())
        engine.process(room(), error()) // 状态提交为 ERROR
        val outcome = engine.process(room(), offline())
        assertFalse(outcome.transient)
        assertEquals(LiveState.OFFLINE, outcome.result.state)
        assertEquals(RoomStatusKeys.OFFLINE, outcome.persistedStatus)
    }

    // ---------------- transient 细节 ----------------

    @Test
    fun transientKeepsLastKnownTitleWhenRawTitleEmpty() {
        val engine = newEngine()
        engine.process(room(), live("最近标题"))
        val transient = engine.process(room(), error())
        assertTrue(transient.transient)
        // 网络异常时用最近一次已知标题补全展示
        assertEquals("最近标题", transient.result.title)
        // 连续第 2 次 ERROR 提交 ERROR 状态，此时平台没有给新标题
        val committed = engine.process(room(), error())
        assertFalse(committed.transient)
        assertEquals(LiveState.ERROR, committed.result.state)
        assertEquals("", committed.result.title)
    }

    @Test
    fun transientDropsUntrustedLiveStartTime() {
        val engine = newEngine()
        engine.process(room(), live("t", startAt = nowMillis - 60_000L))
        val transient = engine.process(room(), error())
        assertTrue(transient.transient)
        assertNull(transient.result.liveStartAt)
    }

    @Test
    fun liveStartAtIsSanitized() {
        val engine = newEngine()
        // 未来 2 小时：不可信，丢弃
        val future = engine.process(room(), live(startAt = nowMillis + 2 * 3_600_000L))
        assertNull(future.result.liveStartAt)

        // 1 小时前：可信，保留
        val engine2 = newEngine()
        val valid = engine2.process(room(), live(startAt = nowMillis - 3_600_000L))
        assertEquals(nowMillis - 3_600_000L, valid.result.liveStartAt ?: -1L)

        // 超过 30 天：丢弃
        val engine3 = newEngine()
        val tooOld = engine3.process(room(), live(startAt = nowMillis - 31L * 24 * 3_600_000L))
        assertNull(tooOld.result.liveStartAt)
    }

    // ---------------- 状态管理 ----------------

    @Test
    fun lastKnownStateTracksCommittedStates() {
        val engine = newEngine()
        assertNull(engine.lastKnownState(room().key))
        engine.process(room(), live())
        assertEquals(LiveState.LIVE, engine.lastKnownState(room().key))
        engine.process(room(), offline())
        assertEquals(LiveState.OFFLINE, engine.lastKnownState(room().key))
    }

    @Test
    fun removeStateMakesNextResultFirstCheck() {
        val engine = newEngine(cooldownSec = 0)
        assertTrue(engine.process(room(), live()).becameLive)
        engine.removeState(room().key)
        assertNull(engine.lastKnownState(room().key))
        // 视为首次检测 → 再次提醒
        assertTrue(engine.process(room(), live()).becameLive)
    }

    @Test
    fun resetClearsAllRooms() {
        val engine = newEngine()
        engine.process(room("1"), live())
        engine.process(room("2"), offline())
        engine.reset()
        assertNull(engine.lastKnownState(room("1").key))
        assertNull(engine.lastKnownState(room("2").key))
    }

    @Test
    fun roomsAreIndependent() {
        val engine = newEngine(cooldownSec = 0)
        assertTrue(engine.process(room("1"), live()).becameLive)
        // 另一个房间的首次检测不受第一个房间冷却/状态影响
        assertTrue(engine.process(room("2"), live()).becameLive)
        engine.process(room("1"), offline())
        assertFalse(engine.process(room("2"), live()).becameLive)
    }
}
