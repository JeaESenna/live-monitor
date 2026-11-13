package com.livemonitor.app.core.platform

import com.livemonitor.app.core.model.Platform
import okhttp3.HttpUrl

/**
 * 访问目标越界时抛出的策略异常（只在 core/platform 内部流转，不向 UI/日志暴露 URL）。
 */
internal class UrlPolicyViolation(message: String = "地址不在允许范围内") : Exception(message)

/**
 * 单次 HTTP 请求前的 URL 策略校验（每次重定向都会重新调用）。
 *
 * 用函数式接口而不是直接写死，方便单元测试注入“只允许 MockWebServer”的测试策略；
 * 生产环境固定使用 [PlatformUrlPolicy]。
 */
internal fun interface UrlPolicy {
    fun validate(url: HttpUrl)
}

/**
 * 平台域名的精确白名单（大小写不敏感，不做通配符匹配）。
 *
 * 安全目标（防 SSRF / 防配置注入 / 防被平台页面误导到任意主机）：
 * - 只允许访问这 5 个平台的公开页面和公开接口；
 * - 域名必须精确匹配，`www.douyu.com.evil.example`、`douyu.com@evil` 之类一律拒绝；
 * - 不访问任何 IP 字面量、内网域名、localhost。
 */
internal object PlatformHosts {

    /** `check()` 过程中允许请求的精确 host（含公开 API 域名）。 */
    val requestHosts: Map<Platform, Set<String>> = mapOf(
        Platform.DOUYU to setOf("www.douyu.com", "open.douyucdn.cn"),
        Platform.BILIBILI to setOf(
            "live.bilibili.com",
            "space.bilibili.com",
            "www.bilibili.com",
            "api.live.bilibili.com",
        ),
        Platform.HUYA to setOf("www.huya.com", "m.huya.com"),
        Platform.DOUYIN to setOf("live.douyin.com"),
        Platform.KUAISHOU to setOf("live.kuaishou.com"),
    )

    /** 持久化“直播间 URL”允许的精确 host（只保存用户可见的房间页地址）。 */
    val storedUrlHosts: Map<Platform, Set<String>> = mapOf(
        Platform.DOUYU to setOf("www.douyu.com"),
        Platform.BILIBILI to setOf("live.bilibili.com", "space.bilibili.com"),
        Platform.HUYA to setOf("www.huya.com"),
        Platform.DOUYIN to setOf("live.douyin.com"),
        Platform.KUAISHOU to setOf("live.kuaishou.com"),
    )

    private val allRequestHosts: Set<String> = requestHosts.values.flatten().toSet()

    private val allStoredHosts: Set<String> = storedUrlHosts.values.flatten().toSet()

    fun isAllowedRequestHost(host: String?): Boolean =
        host != null && allRequestHosts.contains(host.lowercase())

    fun isAllowedStoredHost(host: String?): Boolean =
        host != null && allStoredHosts.contains(host.lowercase())
}

/**
 * 生产环境 HTTP 策略：仅 https；端口只允许 443（或省略）；禁止 URL 内嵌账号密码；
 * host 必须属于平台精确白名单。
 *
 * 说明：禁止 http 是纵深防御——Manifest 已经 `usesCleartextTraffic=false`，
 * 但它管不到重定向（OkHttp 默认的 https→http 重定向在部分 ROM 上仍会尝试），
 * 这里在每次请求/重定向前再拦一次。
 */
internal object PlatformUrlPolicy : UrlPolicy {
    override fun validate(url: HttpUrl) {
        if (!url.isHttps) throw UrlPolicyViolation("仅允许 https")
        if (url.port != 443) throw UrlPolicyViolation("仅允许标准端口")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw UrlPolicyViolation("URL 不允许携带认证信息")
        if (!PlatformHosts.isAllowedRequestHost(url.host)) throw UrlPolicyViolation("域名不在允许范围")
    }
}
