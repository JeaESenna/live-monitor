package com.livemonitor.app.core.notify

import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState

/**
 * 开播提醒 / 后台轮询策略的**纯逻辑**（不引用任何 Android 类，可直接在 JVM 单元测试中验证）。
 *
 * 行为来源：桌面版 `MainForm.OnMonitorEvent / ShowNotifyPopup` 与 `NotifyForm`：
 * - 同一主播 3 分钟冷却由 [com.livemonitor.app.core.monitor.MonitorEngine] 负责，本类只管
 *   “这一条提醒该不该发 / 通知长什么样 / 循环音与自动关闭的时间”；
 * - 移动端只保留“本地开播提醒”语义：**不跳转直播间、不打开浏览器**，所有文案只来自
 *   本地已清洗的数据。
 *
 * Android 侧只做薄封装：[NotificationHelper] 负责渠道/通知构造，[AlertNotifier] 负责
 * 提醒生命周期与循环音，二者都直接复用本类的判定。
 */
object AlertPolicy {

    /** 同时存在的提醒上限（超过后取消最旧的一条，与桌面版弹窗上限一致）。 */
    const val MAX_ACTIVE_ALERTS = 4

    /**
     * 提醒通知 ID 的基础值：范围 [2000, 12000)。
     * - 与前台服务常驻通知 ID（1001）不重叠；
     * - 永远不会是 0（0 是“无效通知 ID”的常见约定，部分 ROM 的取消逻辑会误伤）。
     */
    const val NOTIFICATION_ID_BASE = 2000
    const val NOTIFICATION_ID_RANGE = 10_000

    // 通知文案长度上限：防止异常页面把通知撑爆/被系统截断得莫名其妙。
    const val MAX_WHO_CHARS = 80
    const val MAX_BODY_CHARS = 160
    const val MAX_BIG_TEXT_CHARS = 260
    const val MAX_NAME_CHARS = 40
    const val MAX_PLATFORM_CHARS = 10
    const val MAX_AREA_CHARS = 40
    const val MAX_SUB_CHARS = 20

    /** 主播名与平台之间的分隔符（与桌面版弹窗一致）。 */
    const val SEPARATOR = " · "

    /** 提醒通知上的服务端动作（用于生成互不相同的 PendingIntent requestCode）。 */
    enum class AlertAction { DISMISS, MUTE, ALERT_DISMISSED }

    /** 提醒通知上的 Activity 动作（与 Service 动作使用不同的请求码命名空间）。 */
    enum class ActivityAction { CONTENT, ENTER_ROOM }

    /**
     * roomKey -> 稳定的通知 ID。使用 floorMod（而不是 abs）以处理
     * `String.hashCode() == Int.MIN_VALUE` 的极端 key（如 "polygenelubricants"），
     * 否则 `abs(Int.MIN_VALUE)` 仍是负数，会生成非法通知 ID。
     */
    fun notificationId(roomKey: String): Int =
        NOTIFICATION_ID_BASE + Math.floorMod(roomKey.hashCode(), NOTIFICATION_ID_RANGE)

    /**
     * 在 [notificationId] 基础上解决“不同房间哈希碰撞”的 ID 冲突：
     * 从基础 ID 开始线性探测一个当前未被占用的 ID（仍在 [NOTIFICATION_ID_BASE, +RANGE) 内）。
     *
     * 提醒最多只有 [MAX_ACTIVE_ALERTS] 条，实际几乎不可能走到兜底分支。
     */
    fun resolveNotificationId(roomKey: String, usedIds: Set<Int>): Int {
        val base = notificationId(roomKey)
        val end = NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE
        var candidate = base
        repeat(NOTIFICATION_ID_RANGE) {
            if (candidate !in usedIds) return candidate
            candidate = if (candidate + 1 >= end) NOTIFICATION_ID_BASE else candidate + 1
        }
        return base
    }

    /** 提醒动作的 PendingIntent requestCode（同一通知 ID 下三种动作互不冲突）。 */
    fun alertServiceRequestCode(notificationId: Int, action: AlertAction): Int =
        notificationId * 8 + 1 + action.ordinal.coerceIn(0, 2)

    /**
     * 提醒通知里 Activity 型 PendingIntent 的 requestCode。
     *
     * 规则：每个通知 ID 保留 4 个连续请求码，[ActivityAction] 占用低两位；
     * 起点是 `NOTIFICATION_ID_BASE * 4`（>= 8000），不会与前台常驻通知的固定请求码冲突。
     * 同一通知的“点击打开 App”和“进入直播间”必须不同，否则 FLAG_UPDATE_CURRENT 会让
     * 后创建者覆盖前者的 extras/组件。
     */
    fun alertActivityRequestCode(notificationId: Int, action: ActivityAction): Int =
        notificationId * ACTIVITY_REQUEST_CODE_STRIDE + action.ordinal

    private const val ACTIVITY_REQUEST_CODE_STRIDE = 4

    /** 提醒通知的展示文案（全部已清洗控制字符并按上限截断）。 */
    data class AlertText(
        val who: String,
        val body: String,
        val bigText: String,
    )

    /**
     * 构造提醒通知文案：
     * - who：`主播名(平台昵称) · 平台名`（昵称与显示名相同时不重复）；
     * - body：`[分区] [轮播中] 直播标题`，标题为空时用 [fallbackTitle]（如“正在直播”）；
     * - bigText：展开后的完整两行。
     */
    fun buildAlertText(
        displayName: String?,
        owner: String?,
        platformName: String?,
        area: String?,
        sub: String?,
        title: String?,
        fallbackTitle: String,
    ): AlertText {
        val name = sanitize(displayName, MAX_NAME_CHARS)
        val ownerName = sanitize(owner, MAX_NAME_CHARS)
        val platform = sanitize(platformName, MAX_PLATFORM_CHARS)

        var who = name
        if (ownerName.isNotEmpty() && ownerName != name) {
            who = if (who.isEmpty()) ownerName else "$who($ownerName)"
        }
        if (platform.isNotEmpty()) {
            who = if (who.isEmpty()) platform else "$who$SEPARATOR$platform"
        }
        who = truncate(clean(who), MAX_WHO_CHARS)

        var info = clean(title)
        if (info.isEmpty()) info = clean(fallbackTitle)
        val subText = sanitize(sub, MAX_SUB_CHARS)
        // “网络异常”只是检测瞬时失败，不是主播的真实状态，不写进提醒文案。
        if (subText.isNotEmpty() && subText != CheckResult.SUB_NETWORK_ANOMALY) {
            info = "[$subText] $info"
        }
        val areaText = sanitize(area, MAX_AREA_CHARS)
        if (areaText.isNotEmpty()) {
            info = "[$areaText] $info"
        }
        val body = truncate(info, MAX_BODY_CHARS)
        val big = truncate(
            buildString {
                if (who.isNotEmpty()) append(who).append('\n')
                append(body)
            },
            MAX_BIG_TEXT_CHARS,
        )
        return AlertText(who = who, body = body, bigText = big)
    }

    /**
     * 是否应该为该房间发提醒（冷却已由 MonitorEngine 处理）：
     * [becameLive] 开播事件、房间提醒开关、本次开播是否被“不再提醒”静音、
     * 以及“启动时对已在直播的房间是否提醒”行为。
     */
    fun shouldAlert(
        becameLive: Boolean,
        roomNotify: Boolean,
        muted: Boolean,
        firstCheck: Boolean,
        notifyAlreadyLive: Boolean,
    ): Boolean = becameLive && roomNotify && !muted && (!firstCheck || notifyAlreadyLive)

    /** 是否需要循环提示音（提示音总开关打开且间隔 > 0）。 */
    fun shouldLoop(soundOn: Boolean, loopSoundSec: Int): Boolean = soundOn && loopSoundSec > 0

    /** 循环提示音间隔毫秒；0 表示不循环。 */
    fun loopIntervalMillis(loopSoundSec: Int): Long =
        loopSoundSec.coerceIn(0, AppSettings.MAX_LOOP_SEC) * 1000L

    /**
     * 首声（由 [AlertSoundPlayer] 播放）之后，第一次循环音的等待毫秒数。
     *
     * 规则：`loopSoundSec + 3s` 保护间隔——首声与循环音共用同一个播放器、
     * 且首声可能是较长的自定义音频，额外 3 秒能保证两次声音不会重叠；
     * 循环开始后每 [loopIntervalMillis] 毫秒重复一次。
     */
    fun firstLoopDelayMillis(loopSoundSec: Int): Long {
        val interval = loopIntervalMillis(loopSoundSec)
        return if (interval <= 0L) 0L else interval + FIRST_LOOP_GUARD_MS
    }

    /** 首声与第一次循环音之间的额外保护间隔。 */
    const val FIRST_LOOP_GUARD_MS = 3_000L

    /**
     * 提醒自动关闭的时刻；`0 = 常驻直到用户操作`（与桌面版设置语义一致）。
     */
    fun autoCloseAt(shownAtMillis: Long, popupAutoCloseSec: Int): Long? {
        if (popupAutoCloseSec <= 0) return null
        val sec = popupAutoCloseSec.coerceIn(1, AppSettings.MAX_AUTO_CLOSE_SEC)
        return shownAtMillis + sec * 1000L
    }

    /**
     * 连续失败后的检测间隔倍率：`min(2^n, 4)`，即 1、2、4、4…
     * 用于“连续网络失败指数退避”，避免后台高频重试打平台/耗电。
     */
    fun backoffFactor(consecutiveFailures: Int): Int = 1 shl consecutiveFailures.coerceIn(0, 2)

    /** 下一轮检测的等待毫秒数：interval × 退避倍率。 */
    fun nextCheckDelayMillis(intervalSec: Int, consecutiveFailures: Int): Long {
        val sec = intervalSec.coerceIn(AppSettings.MIN_INTERVAL_SEC, AppSettings.MAX_INTERVAL_SEC)
        return sec * 1000L * backoffFactor(consecutiveFailures)
    }

    /**
     * 这次检测结果是否算“可重试的网络失败”（用于指数退避）。
     * 只有 ERROR 且不是明确的解析/策略失败才计入，NOT_FOUND 是平台给出的确定答案，不计入。
     */
    fun isRetryableFailure(result: CheckResult): Boolean {
        if (result.state != LiveState.ERROR) return false
        return when (result.failure) {
            FailureKind.TIMEOUT,
            FailureKind.NETWORK,
            FailureKind.HTTP_STATUS,
            FailureKind.UNKNOWN,
            FailureKind.NONE -> true

            FailureKind.PARSE, FailureKind.POLICY -> false
        }
    }

    /**
     * 清洗用户/平台文本：去掉所有控制字符（防通知注入）、压缩空白、裁剪到上限。
     * 与 `AppRepository.sanitizeText` 行为一致，但本类不依赖仓储/Android。
     */
    fun sanitize(input: String?, maxChars: Int): String {
        if (input.isNullOrEmpty() || maxChars <= 0) return ""
        val sb = StringBuilder(minOf(input.length, maxChars))
        var lastWasSpace = false
        for (ch in input) {
            if (sb.length >= maxChars) break
            if (ch.isWhitespace()) {
                if (!lastWasSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else if (!isControlChar(ch)) {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** 截断到 [maxChars] 个字符，超出部分用省略号（长度仍不超过上限）。 */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        return if (maxChars == 1) "…" else text.take(maxChars - 1) + "…"
    }

    private fun clean(input: String?): String =
        sanitize(input, (input?.length ?: 0) + 1)

    private fun isControlChar(ch: Char): Boolean =
        ch.code < 0x20 || ch.code == 0x7f || ch.code in 0x80..0x9f
}
