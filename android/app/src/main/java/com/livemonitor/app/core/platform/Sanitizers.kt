package com.livemonitor.app.core.platform

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 平台展示文本的防御性清洗工具（纯 JVM，无 android.* 依赖，可直接被单元测试覆盖）。
 *
 * 这些字段来自平台公开页面/接口，属于不可信输入：
 * - 去掉所有控制字符（含 C0/C1、CR/LF），防止通知栏/界面注入与日志注入；
 * - 压缩连续空白、去掉首尾空白；
 * - 严格限制长度，避免超长文本撑爆通知/界面/持久化；
 * - 解码页面里出现字面量 `\uXXXX` 转义（B站/快手内嵌 JSON 常见）。
 */
internal object PlatformText {

    /** 各展示字段的最大字符数（与 RoomItem 的持久化上限保持一致）。 */
    const val MAX_TITLE_LEN = 120
    const val MAX_OWNER_LEN = 60
    const val MAX_AREA_LEN = 40
    const val MAX_SUB_LEN = 20
    const val MAX_ERROR_LEN = 120
    const val MAX_SRC_LEN = 40

    /** 只为短字段做转义解码；超长输入直接跳过，避免无意义扫描。 */
    private const val MAX_UNESCAPE_INPUT = 10_000

    private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

    /** 清洗不可信文本：去控制字符、压缩空白、限长。 */
    fun sanitize(input: String?, maxChars: Int): String {
        if (input.isNullOrEmpty() || maxChars <= 0) return ""
        val out = StringBuilder(minOf(input.length, maxChars))
        var lastWasSpace = false
        for (ch in input) {
            if (out.length >= maxChars) break
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch.isWhitespace()) {
                if (!lastWasSpace && out.isNotEmpty()) {
                    out.append(' ')
                    lastWasSpace = true
                }
            } else if (ch.code >= 0x20 && ch.code != 0x7f && ch.code !in 0x80..0x9f) {
                out.append(ch)
                lastWasSpace = false
            }
        }
        return out.toString().trim()
    }

    /** 解码页面里字面量形式的 `\uXXXX`（不会抛异常；非法转义原样保留）。 */
    fun unescapeUnicode(input: String?): String {
        if (input.isNullOrEmpty() || !input.contains("\\u")) return input.orEmpty()
        if (input.length > MAX_UNESCAPE_INPUT) return input
        return UNICODE_ESCAPE.replace(input) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code == null) match.value else code.toChar().toString()
        }
    }

    /** 平台字段的通用处理：先解码转义，再清洗 + 限长。 */
    fun displayText(input: String?, maxChars: Int): String =
        sanitize(unescapeUnicode(input), maxChars)

    /**
     * 直播分区：优先显示小类，没有小类时回退到大类（与桌面版 [PickArea] 一致）。
     */
    fun pickArea(parent: String?, area: String?): String {
        val cleanArea = displayText(area, MAX_AREA_LEN)
        if (cleanArea.isNotEmpty()) return cleanArea
        return displayText(parent, MAX_AREA_LEN)
    }
}

/**
 * 平台时间字段解析（纯 JVM）。
 *
 * 平台返回的开播时间格式五花八门，且偶尔出现 `0000-00-00`、缓存旧值、
 * 未来时间等脏数据；这里只负责“解析出 epoch millis”，是否可信由
 * [PlatformApi.sanitizeLiveStartAt] 再过滤一次。
 */
internal object PlatformTime {

    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
    )

    /** 秒级 Unix 时间戳上限（约公元 2286 年），防止乘法溢出。 */
    private const val MAX_UNIX_SECONDS = 10_000_000_000L

    /** 解析 "2026-08-15 14:30:00" 之类的本地时间字符串；不合法返回 null。 */
    fun parseDate(text: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty() || value.length > 40) return null
        for (formatter in DATE_FORMATS) {
            try {
                val local = LocalDateTime.parse(value, formatter)
                if (local.year < 2000) return null
                return local.atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // 尝试下一种格式
            } catch (_: RuntimeException) {
                // 防御性兜底：任何时间解析异常都只意味着“这个字段不可用”
            }
        }
        return null
    }

    /** 解析秒级 Unix 时间戳（B站 live_start_time 等）；明显不合理返回 null。 */
    fun parseUnixSeconds(value: Long?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (value == null || value <= 0L || value > MAX_UNIX_SECONDS) return null
        val millis = value * 1000L
        return try {
            val year = Instant.ofEpochMilli(millis).atZone(zone).year
            if (year < 2000) null else millis
        } catch (_: RuntimeException) {
            null
        }
    }
}
