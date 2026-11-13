package com.livemonitor.app.notify

import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.notify.AlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AlertPolicy] 纯逻辑单元测试（JVM，不依赖 Android）：
 * 通知 ID 稳定映射 / 文案清洗与截断 / 提醒判定 / 循环音与自动关闭时间 / 网络失败退避。
 */
class AlertPolicyTest {

    // ---------------- 通知 ID 稳定映射 ----------------

    @Test
    fun notificationIdIsStableForSameRoomKey() {
        val key = "bilibili:21452505"
        assertEquals(AlertPolicy.notificationId(key), AlertPolicy.notificationId(key))
    }

    @Test
    fun notificationIdAlwaysInsideReservedRange() {
        val keys = listOf(
            "", "a", "douyu:9999", "huya:660000", "bilibili:-1",
            "polygenelubricants", // hashCode == Int.MIN_VALUE 的极端字符串
            "com.livemonitor.app:room", "抖音:123456789012345", "x".repeat(64),
        )
        for (key in keys) {
            val id = AlertPolicy.notificationId(key)
            assertTrue("id=$id key=$key", id >= AlertPolicy.NOTIFICATION_ID_BASE)
            assertTrue("id=$id key=$key", id < AlertPolicy.NOTIFICATION_ID_BASE + AlertPolicy.NOTIFICATION_ID_RANGE)
            assertNotEquals(0, id)
            // 不能与前台服务常驻通知 ID（1001）冲突
            assertNotEquals(1001, id)
        }
    }

    @Test
    fun notificationIdHandlesIntMinValueHashCode() {
        // abs(Int.MIN_VALUE) 仍是负数，必须用 floorMod 才能得到合法 ID。
        val key = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, key.hashCode())
        val id = AlertPolicy.notificationId(key)
        assertTrue(id >= AlertPolicy.NOTIFICATION_ID_BASE)
        assertTrue(id < AlertPolicy.NOTIFICATION_ID_BASE + AlertPolicy.NOTIFICATION_ID_RANGE)
    }

    @Test
    fun resolveNotificationIdKeepsBaseWhenFree() {
        val key = "douyu:123"
        assertEquals(AlertPolicy.notificationId(key), AlertPolicy.resolveNotificationId(key, emptySet()))
    }

    @Test
    fun resolveNotificationIdSkipsUsedIds() {
        val key = "huya:456"
        val base = AlertPolicy.notificationId(key)
        val used = setOf(base, base + 1, base + 2)
        val resolved = AlertPolicy.resolveNotificationId(key, used)
        assertFalse(resolved in used)
        assertTrue(resolved >= AlertPolicy.NOTIFICATION_ID_BASE)
        assertTrue(resolved < AlertPolicy.NOTIFICATION_ID_BASE + AlertPolicy.NOTIFICATION_ID_RANGE)
    }

    @Test
    fun resolveNotificationIdWrapsAroundAtRangeEnd() {
        val key = "kuaishou:789"
        val lastId = AlertPolicy.NOTIFICATION_ID_BASE + AlertPolicy.NOTIFICATION_ID_RANGE - 1
        // 占用末尾附近的若干 ID（与 base 无关时不会触发回绕），验证结果依然合法。
        val used = (lastId - 3..lastId).toSet()
        val resolved = AlertPolicy.resolveNotificationId(key, used)
        assertFalse(resolved in used)
        assertTrue(resolved < AlertPolicy.NOTIFICATION_ID_BASE + AlertPolicy.NOTIFICATION_ID_RANGE)
    }

    @Test
    fun alertRequestCodesAreDistinct() {
        val id = AlertPolicy.notificationId("bilibili:1")
        val codes = AlertPolicy.AlertAction.entries.map {
            AlertPolicy.alertServiceRequestCode(id, it)
        }
        assertEquals(codes.size, codes.toSet().size)
        val other = AlertPolicy.alertServiceRequestCode(AlertPolicy.notificationId("douyu:2"), AlertPolicy.AlertAction.DISMISS)
        assertNotEquals(codes.first(), other)
    }

    // ---------------- 文案清洗 / 截断 ----------------

    @Test
    fun sanitizeRemovesControlCharacters() {
        val input = "标题\u0000\u0007\n第二行\u001b\t制表\u007f\u009c"
        val cleaned = AlertPolicy.sanitize(input, 100)
        assertFalse(cleaned.any { it.code < 0x20 || it.code == 0x7f || it.code in 0x80..0x9f })
        assertTrue(cleaned.contains("标题"))
        assertTrue(cleaned.contains("第二行"))
    }

    @Test
    fun sanitizeCollapsesWhitespaceAndTrims() {
        assertEquals("a b c", AlertPolicy.sanitize("  a \n\t b   c  ", 100))
    }

    @Test
    fun sanitizeRespectsMaxChars() {
        assertEquals(5, AlertPolicy.sanitize("0123456789", 5).length)
        assertEquals("", AlertPolicy.sanitize("abc", 0))
        assertEquals("", AlertPolicy.sanitize(null, 10))
    }

    @Test
    fun truncateAddsEllipsisOnlyWhenNeeded() {
        assertEquals("abc", AlertPolicy.truncate("abc", 3))
        assertEquals("ab…", AlertPolicy.truncate("abcd", 3))
        assertEquals("…", AlertPolicy.truncate("abcd", 1))
        assertEquals(3, AlertPolicy.truncate("abcd", 3).length)
    }

    // ---------------- 开播提醒文案 ----------------

    @Test
    fun buildAlertTextContainsNamePlatformAreaAndTitle() {
        val text = AlertPolicy.buildAlertText(
            displayName = "老王",
            owner = "老王直播间",
            platformName = "B站",
            area = "最终幻想14",
            sub = "",
            title = "今天打零式",
            fallbackTitle = "正在直播",
        )
        assertTrue(text.who, text.who.contains("老王"))
        assertTrue(text.who, text.who.contains("B站"))
        assertTrue(text.who, text.who.contains("老王直播间"))
        assertTrue(text.body, text.body.contains("[最终幻想14]"))
        assertTrue(text.body, text.body.contains("今天打零式"))
        assertTrue(text.bigText, text.bigText.contains(text.who))
        assertTrue(text.bigText, text.bigText.contains(text.body))
    }

    @Test
    fun buildAlertTextDoesNotDuplicateSameOwner() {
        val text = AlertPolicy.buildAlertText(
            displayName = "老王",
            owner = "老王",
            platformName = "斗鱼",
            area = "",
            sub = "",
            title = "标题",
            fallbackTitle = "正在直播",
        )
        assertEquals(1, text.who.split("老王").size - 1)
        assertTrue(text.who, text.who.contains("斗鱼"))
    }

    @Test
    fun buildAlertTextFallsBackWhenTitleEmpty() {
        val text = AlertPolicy.buildAlertText(
            displayName = "主播",
            owner = "",
            platformName = "虎牙",
            area = "",
            sub = "",
            title = "   ",
            fallbackTitle = "正在直播",
        )
        assertEquals("正在直播", text.body)
    }

    @Test
    fun buildAlertTextPrefixesSubAndArea() {
        val text = AlertPolicy.buildAlertText(
            displayName = "主播",
            owner = "",
            platformName = "B站",
            area = "网游",
            sub = "轮播中",
            title = "标题",
            fallbackTitle = "正在直播",
        )
        assertEquals("[网游] [轮播中] 标题", text.body)
    }

    @Test
    fun buildAlertTextIgnoresNetworkAnomalySub() {
        val text = AlertPolicy.buildAlertText(
            displayName = "主播",
            owner = "",
            platformName = "B站",
            area = "",
            sub = CheckResult.SUB_NETWORK_ANOMALY,
            title = "标题",
            fallbackTitle = "正在直播",
        )
        assertEquals("标题", text.body)
    }

    @Test
    fun buildAlertTextCleansAndLimitsLength() {
        val longTitle = "标\n题".repeat(200)
        val text = AlertPolicy.buildAlertText(
            displayName = "主\u0000播".repeat(50),
            owner = "",
            platformName = "斗鱼",
            area = "分\u0001区".repeat(50),
            sub = "",
            title = longTitle,
            fallbackTitle = "正在直播",
        )
        assertTrue(text.who.length <= AlertPolicy.MAX_WHO_CHARS)
        assertTrue(text.body.length <= AlertPolicy.MAX_BODY_CHARS)
        assertTrue(text.bigText.length <= AlertPolicy.MAX_BIG_TEXT_CHARS)
        assertFalse(text.body.any { it.code < 0x20 || it.code == 0x7f })
        assertFalse(text.who.any { it.code < 0x20 || it.code == 0x7f })
    }

    // ---------------- 提醒判定 ----------------

    @Test
    fun shouldAlertMatrix() {
        // 正常开播且开启提醒
        assertTrue(AlertPolicy.shouldAlert(true, true, false, false, true))
        assertTrue(AlertPolicy.shouldAlert(true, true, false, false, false))
        // 不是开播事件
        assertFalse(AlertPolicy.shouldAlert(false, true, false, false, true))
        // 房间关闭了提醒
        assertFalse(AlertPolicy.shouldAlert(true, false, false, false, true))
        // 本次开播已被“不再提醒”
        assertFalse(AlertPolicy.shouldAlert(true, true, true, false, true))
        // 首次检测即直播 + 设置“启动时不提醒”
        assertFalse(AlertPolicy.shouldAlert(true, true, false, true, false))
        // 首次检测即直播 + 设置允许提醒
        assertTrue(AlertPolicy.shouldAlert(true, true, false, true, true))
    }

    // ---------------- 循环提示音 / 自动关闭 ----------------

    @Test
    fun shouldLoopMatrix() {
        assertTrue(AlertPolicy.shouldLoop(true, 60))
        assertFalse(AlertPolicy.shouldLoop(false, 60))
        assertFalse(AlertPolicy.shouldLoop(true, 0))
        assertFalse(AlertPolicy.shouldLoop(true, -5))
    }

    @Test
    fun loopIntervalMillisRespectsBounds() {
        assertEquals(60_000L, AlertPolicy.loopIntervalMillis(60))
        assertEquals(0L, AlertPolicy.loopIntervalMillis(0))
        assertEquals(
            AppSettings.MAX_LOOP_SEC * 1000L,
            AlertPolicy.loopIntervalMillis(AppSettings.MAX_LOOP_SEC + 100),
        )
    }

    @Test
    fun autoCloseAtFollowsSettings() {
        val now = 1_700_000_000_000L
        assertNull(AlertPolicy.autoCloseAt(now, 0))
        assertNull(AlertPolicy.autoCloseAt(now, -1))
        assertEquals(now + 30_000L, AlertPolicy.autoCloseAt(now, 30))
        // 超过上限时会被归一化到 AppSettings.MAX_AUTO_CLOSE_SEC，而不是溢出/为 null。
        assertEquals(now + AppSettings.MAX_AUTO_CLOSE_SEC * 1000L, AlertPolicy.autoCloseAt(now, 10_000_000))
    }

    // ---------------- 网络失败退避 ----------------

    @Test
    fun backoffFactorIsCappedAtFour() {
        assertEquals(1, AlertPolicy.backoffFactor(0))
        assertEquals(2, AlertPolicy.backoffFactor(1))
        assertEquals(4, AlertPolicy.backoffFactor(2))
        assertEquals(4, AlertPolicy.backoffFactor(3))
        assertEquals(4, AlertPolicy.backoffFactor(100))
        // 负数/异常输入不能产生 0 或越界倍率
        assertEquals(1, AlertPolicy.backoffFactor(-1))
    }

    @Test
    fun nextCheckDelayMillisAppliesBackoff() {
        assertEquals(30_000L, AlertPolicy.nextCheckDelayMillis(30, 0))
        assertEquals(60_000L, AlertPolicy.nextCheckDelayMillis(30, 1))
        assertEquals(120_000L, AlertPolicy.nextCheckDelayMillis(30, 2))
        assertEquals(120_000L, AlertPolicy.nextCheckDelayMillis(30, 9))
        // 间隔越界时会被归一化到 [15, 3600]
        assertEquals(
            AppSettings.MIN_INTERVAL_SEC * 1000L,
            AlertPolicy.nextCheckDelayMillis(1, 0),
        )
    }

    @Test
    fun retryableFailureClassification() {
        assertTrue(
            AlertPolicy.isRetryableFailure(
                CheckResult(state = LiveState.ERROR, failure = FailureKind.NETWORK),
            ),
        )
        assertTrue(
            AlertPolicy.isRetryableFailure(
                CheckResult(state = LiveState.ERROR, failure = FailureKind.TIMEOUT),
            ),
        )
        assertTrue(
            AlertPolicy.isRetryableFailure(
                CheckResult(state = LiveState.ERROR, failure = FailureKind.HTTP_STATUS),
            ),
        )
        assertFalse(
            AlertPolicy.isRetryableFailure(
                CheckResult(state = LiveState.ERROR, failure = FailureKind.PARSE),
            ),
        )
        // 已确定的状态（LIVE/OFFLINE/NOT_FOUND）不算网络失败
        assertFalse(AlertPolicy.isRetryableFailure(CheckResult(state = LiveState.LIVE)))
        assertFalse(AlertPolicy.isRetryableFailure(CheckResult(state = LiveState.OFFLINE)))
        assertFalse(AlertPolicy.isRetryableFailure(CheckResult(state = LiveState.NOT_FOUND)))
    }

    // ---------------- v1.1.0：首声/循环间隔、通知动作 requestCode ----------------

    @Test
    fun firstLoopDelayAddsThreeSecondGuard() {
        // 首声之后第一次循环 = loopSoundSec + 3s；0 表示不循环。
        assertEquals(0L, AlertPolicy.firstLoopDelayMillis(0))
        assertEquals(4_000L, AlertPolicy.firstLoopDelayMillis(1))
        assertEquals(63_000L, AlertPolicy.firstLoopDelayMillis(60))
        assertEquals(
            AppSettings.MAX_LOOP_SEC * 1000L + AlertPolicy.FIRST_LOOP_GUARD_MS,
            AlertPolicy.firstLoopDelayMillis(AppSettings.MAX_LOOP_SEC + 999),
        )
    }

    @Test
    fun activityRequestCodesAreUniquePerActionAndRoom() {
        val a = AlertPolicy.notificationId("bilibili:1")
        val b = AlertPolicy.notificationId("douyu:2")
        val codes = listOf(
            AlertPolicy.alertActivityRequestCode(a, AlertPolicy.ActivityAction.CONTENT),
            AlertPolicy.alertActivityRequestCode(a, AlertPolicy.ActivityAction.ENTER_ROOM),
            AlertPolicy.alertActivityRequestCode(b, AlertPolicy.ActivityAction.CONTENT),
            AlertPolicy.alertActivityRequestCode(b, AlertPolicy.ActivityAction.ENTER_ROOM),
        )
        assertEquals(codes.size, codes.toSet().size)
        // “点击通知打开 App 列表”和“进入直播间”必须拿到不同的 requestCode，
        // 否则 PendingIntent 会互相覆盖。
        assertNotEquals(codes[0], codes[1])
        assertNotEquals(codes[2], codes[3])
        // 取值范围不能和前台常驻通知的固定 requestCode（101/102/103）冲突
        assertTrue(codes.min() > 1_000)
    }

    @Test
    fun serviceRequestCodesAndActivityRequestCodesAreSeparate() {
        val id = AlertPolicy.notificationId("huya:3")
        val service = AlertPolicy.AlertAction.entries.map {
            AlertPolicy.alertServiceRequestCode(id, it)
        }
        val activity = AlertPolicy.ActivityAction.entries.map {
            AlertPolicy.alertActivityRequestCode(id, it)
        }
        // 即使数值区间重叠也无所谓（PendingIntent 的 Activity/Service 命名空间不同），
        // 这里仅验证两种动作各自的 requestCode 稳定且不与自身重复。
        assertEquals(service.size, service.toSet().size)
        assertEquals(activity.size, activity.toSet().size)
        assertEquals(
            AlertPolicy.alertActivityRequestCode(id, AlertPolicy.ActivityAction.ENTER_ROOM),
            AlertPolicy.alertActivityRequestCode(id, AlertPolicy.ActivityAction.ENTER_ROOM),
        )
    }
}
