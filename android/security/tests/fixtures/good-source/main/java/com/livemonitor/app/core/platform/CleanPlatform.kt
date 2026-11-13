package com.livemonitor.app.core.platform

/**
 * SelfTest 夹具：干净代码（good-source 必须 0 FAIL）。
 * 只包含 HTTPS 平台白名单常量，不触发任何禁用规则。
 */
object CleanPlatform {

    private val ALLOWED_HOSTS = setOf(
        "www.douyu.com",
        "www.huya.com",
        "live.bilibili.com",
        "live.douyin.com",
        "live.kuaishou.com",
    )

    fun roomUrl(platform: String, id: String): String? {
        if (id.isEmpty() || id.length > 64) return null
        if (!id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return when (platform) {
            "douyu" -> "https://www.douyu.com/$id"
            "huya" -> "https://www.huya.com/$id"
            "bilibili" -> "https://live.bilibili.com/$id"
            "douyin" -> "https://live.douyin.com/$id"
            "kuaishou" -> "https://live.kuaishou.com/u/$id"
            else -> null
        }
    }
}
