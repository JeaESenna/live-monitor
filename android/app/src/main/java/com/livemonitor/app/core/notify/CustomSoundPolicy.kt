package com.livemonitor.app.core.notify

import com.livemonitor.app.core.model.AppSettings

/**
 * 自定义提示音的**纯逻辑**（不引用任何 Android 类，JVM 单元测试可直接覆盖）：
 * 允许的格式（MIME / 扩展名）、2MB 大小上限、固定文件名构造与合法性校验、界面展示名。
 *
 * 安全说明：
 * - 应用只接受用户通过 SAF 选择的音频，并总是复制到应用内部目录、固定命名为
 *   `custom_alert.<ext>`；原始文件名/路径只用于推断扩展名，绝不落盘、不写日志；
 * - 因此这里只需要处理“扩展名”，不存在路径穿越问题（targetFileName 永远以固定前缀开头）。
 */
object CustomSoundPolicy {

    /** 单个自定义提示音大小上限：2MB（避免占用过多内部存储、也避免解码超大文件）。 */
    const val MAX_BYTES: Long = 2L * 1024 * 1024

    /** 内部固定文件名前缀。 */
    const val TARGET_PREFIX = "custom_alert"

    /** 允许的扩展名（与 AppSettings.CUSTOM_SOUND_PATTERN 保持一致）。 */
    val ALLOWED_EXTENSIONS: Set<String> = setOf("wav", "mp3", "ogg", "m4a", "aac")

    /** 精确 MIME -> 扩展名映射（不含参数；大小写不敏感）。 */
    private val MIME_TO_EXTENSION: Map<String, String> = mapOf(
        "audio/wav" to "wav",
        "audio/wave" to "wav",
        "audio/x-wav" to "wav",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/x-mp3" to "mp3",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/x-aac" to "aac",
    )

    /**
     * 根据 ContentResolver 返回的 MIME 与文件显示名推断要保存的扩展名；不支持时返回 null。
     *
     * 规则（与需求一致：MIME 为 audio 前缀 **或** 扩展名受支持都算合格）：
     * 1. MIME 有精确映射 → 用映射（兼容 provider 给的显示名不规范的情况）；
     * 2. MIME 是其它 audio 前缀但无映射 → 退回到受支持的扩展名；
     * 3. MIME 缺失/非 audio（部分 SAF provider 返回 application/octet-stream）→
     *    只接受受支持的扩展名。
     */
    fun extensionFor(mimeType: String?, displayName: String?): String? {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val byMime = MIME_TO_EXTENSION[mime]
        if (byMime != null) return byMime
        val byName = extensionFromName(displayName)
        if (mime.startsWith("audio/")) {
            // audio 前缀但无法确定具体格式：只有在文件名扩展名受支持时才能确定保存格式。
            return byName
        }
        return byName
    }

    /** 从文件名中提取受支持的扩展名（小写）；无扩展名/不支持返回 null。 */
    fun extensionFromName(displayName: String?): String? {
        if (displayName.isNullOrEmpty()) return null
        // 只取最后一段的文件名，忽略任何目录部分；不使用该路径本身。
        val baseName = displayName.substringAfterLast('/').substringAfterLast('\\')
        val dot = baseName.lastIndexOf('.')
        if (dot <= 0 || dot == baseName.length - 1) return null
        val extension = baseName.substring(dot + 1).lowercase()
        return if (extension in ALLOWED_EXTENSIONS) extension else null
    }

    /** 大小是否符合要求：>0 且 <= [MAX_BYTES]。 */
    fun isAllowedSize(byteCount: Long): Boolean = byteCount in 1..MAX_BYTES

    /** 写入内部目录的固定文件名（如 `custom_alert.mp3`）。 */
    fun targetFileName(extension: String): String? {
        val clean = extension.lowercase()
        if (clean !in ALLOWED_EXTENSIONS) return null
        return "$TARGET_PREFIX.$clean"
    }

    /** 存储里的文件名是否合法（只可能是 [targetFileName] 产出的固定名）。 */
    fun isValidStoredFileName(fileName: String?): Boolean =
        !fileName.isNullOrEmpty() && AppSettings.CUSTOM_SOUND_PATTERN.matches(fileName)

    /** 当前提示音的展示名：没有合法自定义文件时显示 [builtInLabel]。 */
    fun displayName(fileName: String?, builtInLabel: String): String =
        if (isValidStoredFileName(fileName)) fileName!! else builtInLabel
}
