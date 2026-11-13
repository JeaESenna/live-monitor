package com.livemonitor.app.notify

import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.notify.AlertCenter
import com.livemonitor.app.core.notify.AlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AlertCenter] 纯逻辑单元测试：
 * 同时最多 4 条提醒与最旧淘汰 / 同房间重复提醒复用通知 ID / mute 生命周期 /
 * 循环提示音间隔聚合 / 自动关闭到期判定。
 */
class AlertCenterTest {

    private fun center() = AlertCenter()

    @Test
    fun showKeepsAtMostFourAlertsAndEvictsOldest() {
        val center = center()
        center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.show("room:2", 2_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.show("room:3", 3_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.show("room:4", 4_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        val fifth = center.show("room:5", 5_000L, loopSoundSec = 0, popupAutoCloseSec = 0)

        assertEquals(AlertPolicy.MAX_ACTIVE_ALERTS, center.activeCount())
        assertEquals(1, fifth.evicted.size)
        assertEquals("room:1", fifth.evicted.first().roomKey)
        val keys = center.alerts().map { it.roomKey }
        assertEquals(listOf("room:2", "room:3", "room:4", "room:5"), keys)
    }

    @Test
    fun repeatedRoomAlertReusesNotificationIdAndBecomesNewest() {
        val center = center()
        val first = center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.show("room:2", 2_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        val again = center.show("room:1", 3_000L, loopSoundSec = 0, popupAutoCloseSec = 0)

        assertEquals(2, center.activeCount())
        assertEquals(first.alert.notificationId, again.alert.notificationId)
        assertTrue(again.evicted.isEmpty())
        // 重复提醒后该房间排到最新（淘汰时最后才轮到它）
        assertEquals(listOf("room:2", "room:1"), center.alerts().map { it.roomKey })
    }

    @Test
    fun differentRoomsUsuallyGetDifferentNotificationIds() {
        val center = center()
        val a = center.show("bilibili:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        val b = center.show("douyu:1", 2_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        // 两条提醒同时展示时 ID 必须不同（哈希碰撞时由 AlertCenter 探测避让）
        assertNotEquals(a.alert.notificationId, b.alert.notificationId)
    }

    @Test
    fun muteIsPerSessionAndClearedByReliableOffline() {
        val center = center()
        assertFalse(center.isMuted("room:1"))
        center.mute("room:1")
        assertTrue(center.isMuted("room:1"))

        // 网络异常（瞬时）不清 mute：由调用方只在可靠 OFFLINE 时调用 onReliableOffline
        assertTrue(center.onReliableOffline("room:1"))
        assertFalse(center.isMuted("room:1"))
        // 重复清除返回 false，不报错
        assertFalse(center.onReliableOffline("room:1"))
    }

    @Test
    fun removeOnlyRemovesAlertNotMute() {
        val center = center()
        center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.mute("room:1")
        val result = center.remove("room:1")
        assertEquals("room:1", result.removed?.roomKey)
        assertEquals(0, result.remaining)
        // “忽略”不等于“不再提醒”：mute 仍保持到本次开播结束
        assertTrue(center.isMuted("room:1"))
        assertTrue(center.alerts().isEmpty())
    }

    @Test
    fun removeUnknownRoomReturnsNull() {
        val center = center()
        val result = center.remove("missing")
        assertNull(result.removed)
        assertEquals(0, result.remaining)
    }

    @Test
    fun forgetRemovesAlertAndMute() {
        val center = center()
        center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.mute("room:1")
        val removed = center.forget("room:1")
        assertEquals("room:1", removed?.roomKey)
        assertFalse(center.isMuted("room:1"))
        assertTrue(center.alerts().isEmpty())
    }

    @Test
    fun desiredLoopSecUsesGentlestPositiveValue() {
        val center = center()
        assertEquals(0, center.desiredLoopSec())
        center.show("room:1", 1_000L, loopSoundSec = 60, popupAutoCloseSec = 0)
        assertEquals(60, center.desiredLoopSec())
        center.show("room:2", 2_000L, loopSoundSec = 30, popupAutoCloseSec = 0)
        // 多条提醒共用一个循环播放器时取最温和（最大）的间隔，避免过度打扰
        assertEquals(60, center.desiredLoopSec())
        center.remove("room:1")
        assertEquals(30, center.desiredLoopSec())
    }

    @Test
    fun loopSoundSecIsNormalized() {
        val center = center()
        val alert = center.show("room:1", 1_000L, loopSoundSec = -10, popupAutoCloseSec = 0)
        assertEquals(0, alert.alert.loopSoundSec)
        val huge = center.show("room:2", 2_000L, loopSoundSec = AppSettings.MAX_LOOP_SEC + 100, popupAutoCloseSec = 0)
        assertEquals(AppSettings.MAX_LOOP_SEC, huge.alert.loopSoundSec)
    }

    @Test
    fun autoCloseAtIsZeroMeansPersistent() {
        val center = center()
        val persistent = center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        assertNull(persistent.alert.autoCloseAt)

        val autoClose = center.show("room:2", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 10)
        assertEquals(11_000L, autoClose.alert.autoCloseAt)
    }

    @Test
    fun dueForAutoCloseOnlyReturnsExpiredAlerts() {
        val center = center()
        center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 10) // 11_000 到期
        center.show("room:2", 2_000L, loopSoundSec = 0, popupAutoCloseSec = 60) // 62_000 到期
        center.show("room:3", 3_000L, loopSoundSec = 0, popupAutoCloseSec = 0) // 常驻

        assertTrue(center.dueForAutoClose(10_999L).isEmpty())
        assertEquals(listOf("room:1"), center.dueForAutoClose(11_000L).map { it.roomKey })
        assertEquals(
            listOf("room:1", "room:2"),
            center.dueForAutoClose(100_000L).map { it.roomKey },
        )
    }

    @Test
    fun clearReturnsAllAlertsAndEmptiesCenter() {
        val center = center()
        center.show("room:1", 1_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        center.show("room:2", 2_000L, loopSoundSec = 0, popupAutoCloseSec = 0)
        val cleared = center.clear()
        assertEquals(2, cleared.size)
        assertEquals(0, center.activeCount())
        assertEquals(0, center.desiredLoopSec())
        assertTrue(center.clear().isEmpty())
    }

    @Test
    fun mutedKeysSnapshotDoesNotChangeWithLaterMutations() {
        val center = center()
        center.mute("room:1")
        val snapshot = center.mutedKeys()
        assertTrue("room:1" in snapshot)
        // 快照与内部集合解耦：后续 mute 不影响已取出的快照
        center.mute("room:2")
        assertFalse("room:2" in snapshot)
        assertTrue(center.isMuted("room:2"))
    }
}
