package com.livemonitor.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量本地日志（不依赖任何第三方库，不上传任何数据）。
 *
 * 安全约束（安全评审会检查）：
 * - 绝不记录 Cookie、Authorization、完整 URL、页面内容；
 * - 只允许记录平台 key、房间 id、状态枚举、错误码/简短异常名；
 * - 单条日志长度截断，控制字符剔除，防止日志注入/撑爆磁盘；
 * - 单文件 1MB 轮转，只保留上一份（app.prev.log），总占用上限约 2MB。
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_MESSAGE_CHARS = 2000
    private const val PREV = "app.prev.log"
    private const val CURRENT = "app.log"

    private val lock = Any()

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var timeFormat: SimpleDateFormat? = null

    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        synchronized(lock) {
            if (timeFormat == null) {
                timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            }
        }
    }

    fun info(tag: String, message: String) = write("I", tag, message, null)

    fun error(tag: String, message: String, tr: Throwable? = null) = write("E", tag, message, tr)

    /** 供“诊断日志”界面读取最近 N 行（只读本机文件）。 */
    fun recentLines(maxLines: Int = 200): List<String> {
        val file = currentFile() ?: return emptyList()
        return try {
            synchronized(lock) {
                if (!file.exists()) return emptyList()
                val lines = file.readLines()
                if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        val file = currentFile() ?: return
        try {
            synchronized(lock) {
                if (file.exists()) file.delete()
                val prev = File(file.parentFile, PREV)
                if (prev.exists()) prev.delete()
            }
        } catch (_: Throwable) {
            // 日志清理失败不影响主流程
        }
    }

    private fun currentFile(): File? {
        val dir = logDir ?: return null
        return File(dir, CURRENT)
    }

    private fun write(level: String, tag: String, message: String, tr: Throwable?) {
        val dir = logDir ?: return
        try {
            val safeTag = sanitize(tag, 40)
            val detail = buildString {
                append(sanitize(message, MAX_MESSAGE_CHARS))
                if (tr != null) {
                    append(" | ")
                    append(tr.javaClass.simpleName)
                    val msg = tr.message
                    if (!msg.isNullOrBlank()) {
                        append(": ")
                        append(sanitize(msg, 300))
                    }
                }
            }
            synchronized(lock) {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, CURRENT)
                rotateIfNeeded(file)
                val stamp = timeFormat?.format(Date()) ?: ""
                file.appendText("$stamp [$level] [$safeTag] $detail\n")
            }
        } catch (_: Throwable) {
            // 日志失败必须静默：监测主流程不能因为磁盘/权限问题中断
        }
    }

    private fun rotateIfNeeded(file: File) {
        try {
            if (!file.exists() || file.length() <= MAX_BYTES) return
            val prev = File(file.parentFile, PREV)
            if (prev.exists()) prev.delete()
            if (!file.renameTo(prev)) {
                file.writeText("")
            }
        } catch (_: Throwable) {
            try {
                file.writeText("")
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    /** 剔除控制字符（含 CR/LF/TAB 之外的 C0/C1），限制长度，避免日志注入；
     *  同时对 URL 与疑似凭据字段做脱敏（纵深防御：网络层本身不把 URL 带进异常）。 */
    private fun sanitize(input: String, maxChars: Int): String {
        val redacted = redact(input)
        val sb = StringBuilder(minOf(redacted.length, maxChars))
        for (ch in redacted) {
            if (sb.length >= maxChars) break
            when {
                ch == '\n' || ch == '\r' -> sb.append(' ')
                ch == '\t' -> sb.append(' ')
                ch.code < 0x20 || ch.code == 0x7f -> Unit
                ch.code in 0x80..0x9f -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun redact(input: String): String {
        var out = input
        if (URL_REGEX.containsMatchIn(out)) out = URL_REGEX.replace(out, "[url]")
        if (SECRET_REGEX.containsMatchIn(out)) out = SECRET_REGEX.replace(out, "$1=[redacted]")
        return out
    }

    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val SECRET_REGEX = Regex(
        "(?i)(cookie|token|authorization|password|passwd|secret)\\s*[=:]\\s*\\S+",
    )
}
