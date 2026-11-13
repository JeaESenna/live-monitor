package com.livemonitor.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间展示工具。
 *
 * 与桌面版保持一致：列表里的“上次开播”只显示月日（平台上报的开播时间可能有
 * 几分钟偏差，显示到分钟会引起误解）；“最后检测”显示时分秒。
 */
object TimeFormat {
    private val lastLiveFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
    private val lastCheckFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)
    private val fullFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun lastLive(epochMillis: Long?): String =
        epochMillis?.let { lastLiveFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"

    fun lastCheck(epochMillis: Long?): String =
        epochMillis?.let { lastCheckFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"

    fun full(epochMillis: Long?): String =
        epochMillis?.let { fullFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"
}
