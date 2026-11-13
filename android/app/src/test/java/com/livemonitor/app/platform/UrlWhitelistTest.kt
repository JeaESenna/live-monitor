package com.livemonitor.app.platform

import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.platform.PlatformUrlPolicy
import com.livemonitor.app.core.platform.UrlPolicyViolation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 平台 URL 白名单的离线安全测试（**安全关键**）。
 *
 * 覆盖：
 * - [PlatformApi.normalizeStoredUrl]：只有 https + 443 + 精确平台域名 + id 匹配的
 *   持久化 URL 才接受；http/file/javascript/任意域名/IP/非标准端口/userinfo 全部拒绝；
 * - [PlatformApi.buildUrl]：ID 必须匹配 `[A-Za-z0-9_-]{1,64}`；
 * - [PlatformUrlPolicy]：HTTP 层每次请求/重定向前的最终校验。
 */
class UrlWhitelistTest {

    private fun normalized(platform: Platform, id: String, storedUrl: String?): String? =
        PlatformApi.normalizeStoredUrl(platform, id, storedUrl)

    private fun assertAccepted(
        platform: Platform,
        id: String,
        storedUrl: String?,
        expected: String,
    ) {
        val actual = normalized(platform, id, storedUrl)
        assertEquals("合法 URL 应被规范化", expected, actual)
    }

    private fun assertRejected(platform: Platform, id: String, storedUrl: String?) {
        assertNull("非法 URL 必须返回 null", normalized(platform, id, storedUrl))
    }

    private fun assertPolicyRejects(urlText: String) {
        val url = urlText.toHttpUrlOrNull()
        assertNotNull("测试用地址应能被 OkHttp 解析", url)
        try {
            PlatformUrlPolicy.validate(url!!)
            fail("策略应拒绝该地址")
        } catch (_: UrlPolicyViolation) {
            // 预期
        }
    }

    // ---------------- normalizeStoredUrl 正例 ----------------

    @Test
    fun acceptsValidDouyuUrl() {
        assertAccepted(
            Platform.DOUYU,
            "9999",
            "https://www.douyu.com/9999",
            "https://www.douyu.com/9999",
        )
    }

    @Test
    fun acceptsValidHuyaUrl() {
        assertAccepted(
            Platform.HUYA,
            "660000",
            "https://www.huya.com/660000",
            "https://www.huya.com/660000",
        )
    }

    @Test
    fun acceptsValidBilibiliRoomUrl() {
        assertAccepted(
            Platform.BILIBILI,
            "6",
            "https://live.bilibili.com/6",
            "https://live.bilibili.com/6",
        )
    }

    @Test
    fun acceptsValidBilibiliSpaceUidUrl() {
        assertAccepted(
            Platform.BILIBILI,
            "123456",
            "https://space.bilibili.com/123456",
            "https://space.bilibili.com/123456/live",
        )
        assertAccepted(
            Platform.BILIBILI,
            "123456",
            "https://space.bilibili.com/123456/live",
            "https://space.bilibili.com/123456/live",
        )
    }

    @Test
    fun acceptsValidDouyinUrl() {
        assertAccepted(
            Platform.DOUYIN,
            "123456789012345",
            "https://live.douyin.com/123456789012345",
            "https://live.douyin.com/123456789012345",
        )
    }

    @Test
    fun acceptsValidKuaishouUrl() {
        assertAccepted(
            Platform.KUAISHOU,
            "3xabc123",
            "https://live.kuaishou.com/u/3xabc123",
            "https://live.kuaishou.com/u/3xabc123",
        )
    }

    @Test
    fun normalizesKuaishouProfileUrlToU() {
        assertAccepted(
            Platform.KUAISHOU,
            "3xabc123",
            "https://live.kuaishou.com/profile/3xabc123",
            "https://live.kuaishou.com/u/3xabc123",
        )
    }

    @Test
    fun dropsQueryFragmentAndTrailingSlash() {
        assertAccepted(
            Platform.DOUYU,
            "9999",
            "https://www.douyu.com/9999/?from=share#top",
            "https://www.douyu.com/9999",
        )
        assertAccepted(
            Platform.BILIBILI,
            "6",
            "https://live.bilibili.com/6?broadcast_type=0",
            "https://live.bilibili.com/6",
        )
    }

    @Test
    fun acceptsExplicitStandardPort() {
        assertAccepted(
            Platform.DOUYU,
            "9999",
            "https://www.douyu.com:443/9999",
            "https://www.douyu.com/9999",
        )
    }

    @Test
    fun blankStoredUrlFallsBackToCanonical() {
        assertAccepted(Platform.DOUYU, "9999", null, "https://www.douyu.com/9999")
        assertAccepted(Platform.DOUYU, "9999", "", "https://www.douyu.com/9999")
        assertAccepted(Platform.DOUYU, "9999", "   ", "https://www.douyu.com/9999")
    }

    // ---------------- normalizeStoredUrl 反例（SSRF/协议/注入） ----------------

    @Test
    fun rejectsHttpUrl() {
        assertRejected(Platform.DOUYU, "9999", "http://www.douyu.com/9999")
    }

    @Test
    fun rejectsFileProtocol() {
        assertRejected(Platform.DOUYU, "9999", "file:///C:/Windows/notepad.exe")
    }

    @Test
    fun rejectsJavascriptProtocol() {
        assertRejected(Platform.DOUYU, "9999", "javascript:alert(1)")
    }

    @Test
    fun rejectsDataProtocol() {
        assertRejected(Platform.DOUYU, "9999", "data:text/html,something")
    }

    @Test
    fun rejectsArbitraryDomain() {
        assertRejected(Platform.DOUYU, "9999", "https://evil.example/9999")
    }

    @Test
    fun rejectsHostSuffixAttack() {
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com.evil.example/9999")
    }

    @Test
    fun rejectsIpLiteralHost() {
        assertRejected(Platform.DOUYU, "9999", "https://127.0.0.1/9999")
        assertRejected(Platform.DOUYU, "9999", "https://192.168.1.10/9999")
    }

    @Test
    fun rejectsLocalhost() {
        assertRejected(Platform.DOUYU, "9999", "https://localhost/9999")
    }

    @Test
    fun rejectsNonStandardPort() {
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com:8443/9999")
        assertRejected(Platform.BILIBILI, "6", "https://live.bilibili.com:8080/6")
    }

    @Test
    fun rejectsUserInfo() {
        assertRejected(Platform.DOUYU, "9999", "https://user:pass@www.douyu.com/9999")
        assertRejected(Platform.DOUYU, "9999", "https://user@www.douyu.com/9999")
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com@evil.example/9999")
    }

    @Test
    fun rejectsControlCharacters() {
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com/9999\nX-Injected: 1")
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com/99\u000099")
    }

    @Test
    fun rejectsOverlongUrl() {
        val long = "https://www.douyu.com/9999?" + "a".repeat(600)
        assertTrue(long.length > 512)
        assertRejected(Platform.DOUYU, "9999", long)
    }

    @Test
    fun rejectsIdMismatch() {
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com/8888")
        assertRejected(Platform.BILIBILI, "6", "https://live.bilibili.com/7")
    }

    @Test
    fun rejectsExtraPathSegments() {
        assertRejected(Platform.DOUYU, "9999", "https://www.douyu.com/9999/extra")
        assertRejected(Platform.BILIBILI, "123456", "https://space.bilibili.com/123456/video")
        assertRejected(Platform.BILIBILI, "123456", "https://space.bilibili.com/123456/live/extra")
    }

    @Test
    fun rejectsWrongPlatformHost() {
        assertRejected(Platform.HUYA, "9999", "https://www.douyu.com/9999")
        assertRejected(Platform.DOUYU, "9999", "https://live.bilibili.com/9999")
        assertRejected(Platform.KUAISHOU, "3xabc123", "https://live.kuaishou.com/3xabc123")
        assertRejected(Platform.KUAISHOU, "3xabc123", "https://live.kuaishou.com/u/other")
    }

    @Test
    fun rejectsInvalidId() {
        assertNull(PlatformApi.normalizeStoredUrl(Platform.DOUYU, "bad id", "https://www.douyu.com/9999"))
        assertNull(PlatformApi.normalizeStoredUrl(Platform.DOUYU, "", "https://www.douyu.com/9999"))
        assertNull(PlatformApi.normalizeStoredUrl(Platform.DOUYU, "a/../b", null))
        assertNull(PlatformApi.normalizeStoredUrl(Platform.DOUYU, "a".repeat(65), null))
    }

    @Test
    fun rejectsMalformedUrl() {
        assertRejected(Platform.DOUYU, "9999", "not a url")
        assertRejected(Platform.DOUYU, "9999", "https:///9999")
        assertRejected(Platform.DOUYU, "9999", "//www.douyu.com/9999")
    }

    // ---------------- buildUrl ----------------

    @Test
    fun buildUrlMatchesPlatforms() {
        assertEquals("https://www.douyu.com/9999", PlatformApi.buildUrl(Platform.DOUYU, "9999"))
        assertEquals("https://www.huya.com/660000", PlatformApi.buildUrl(Platform.HUYA, "660000"))
        assertEquals("https://live.bilibili.com/6", PlatformApi.buildUrl(Platform.BILIBILI, "6"))
        assertEquals(
            "https://live.douyin.com/123456789012345",
            PlatformApi.buildUrl(Platform.DOUYIN, "123456789012345"),
        )
        assertEquals(
            "https://live.kuaishou.com/u/3xabc123",
            PlatformApi.buildUrl(Platform.KUAISHOU, "3xabc123"),
        )
        assertEquals("https://www.douyu.com/a_-B1", PlatformApi.buildUrl(Platform.DOUYU, "a_-B1"))
    }

    @Test
    fun buildUrlRejectsIllegalIds() {
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, ""))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "a b"))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "a/b"))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "a?b"))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "a#b"))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "中文"))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "a".repeat(65)))
        assertNull(PlatformApi.buildUrl(Platform.DOUYU, "9999\r\n"))
    }

    // ---------------- 生产环境 URL 策略（HttpFetcher 每次请求/重定向都会调用） ----------------

    @Test
    fun policyAcceptsAllPlatformHosts() {
        val urls = listOf(
            "https://www.douyu.com/9999",
            "https://open.douyucdn.cn/api/RoomApi/room/9999",
            "https://live.bilibili.com/6",
            "https://space.bilibili.com/123456/live",
            "https://www.bilibili.com/",
            "https://api.live.bilibili.com/room/v1/Room/room_init?id=6",
            "https://www.huya.com/660000",
            "https://m.huya.com/660000",
            "https://live.douyin.com/123456789012345",
            "https://live.kuaishou.com/u/3xabc123",
            "https://www.douyu.com:443/9999",
        )
        for (text in urls) {
            val url = text.toHttpUrlOrNull()!!
            PlatformUrlPolicy.validate(url)
        }
    }

    @Test
    fun policyRejectsCleartextHttp() {
        assertPolicyRejects("http://www.douyu.com/9999")
        assertPolicyRejects("http://live.bilibili.com/6")
    }

    @Test
    fun policyRejectsUnknownHosts() {
        assertPolicyRejects("https://evil.example/")
        assertPolicyRejects("https://www.douyu.com.evil.example/9999")
        assertPolicyRejects("https://evildouyu.com/9999")
        assertPolicyRejects("https://localhost/9999")
        assertPolicyRejects("https://127.0.0.1/9999")
        assertPolicyRejects("https://[::1]/9999")
    }

    @Test
    fun policyRejectsNonStandardPortAndUserInfo() {
        assertPolicyRejects("https://www.douyu.com:8443/9999")
        assertPolicyRejects("https://user:pass@www.douyu.com/9999")
    }
}
