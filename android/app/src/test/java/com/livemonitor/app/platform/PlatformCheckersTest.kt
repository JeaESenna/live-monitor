package com.livemonitor.app.platform

import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.platform.FetchOutcome
import com.livemonitor.app.core.platform.HttpTransport
import com.livemonitor.app.core.platform.PlatformChecker
import com.livemonitor.app.core.platform.PlatformHosts
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台检测网络编排层的离线单元测试（假 HTTP 传输，不联网）。
 *
 * 覆盖桌面版每条主路径/降级路径、B站 412 预热重试、错误分类，
 * 并用“记录所有请求”的假传输断言**一次检测只访问平台白名单域名**（安全关键）。
 */
class PlatformCheckersTest {

    private data class Recorded(val url: HttpUrl, val referer: String?)

    private class FakeTransport(
        private val handler: (HttpUrl, String?) -> FetchOutcome,
    ) : HttpTransport {
        val records = mutableListOf<Recorded>()
        val seededCookies = mutableListOf<String>()

        override fun get(url: String, referer: String?): FetchOutcome {
            val parsed = url.toHttpUrlOrNull()
                ?: return FetchOutcome.Failure(FailureKind.POLICY, "bad test url")
            records += Recorded(parsed, referer)
            return handler(parsed, referer)
        }

        override fun seedCookie(host: String, name: String, value: String) {
            seededCookies += "$host/$name"
        }

        fun countHost(host: String): Int = records.count { it.url.host == host }
    }

    private var nowMillis = 1_800_000_000_000L

    private fun checker(transport: HttpTransport): PlatformChecker =
        PlatformChecker(transport, clock = { nowMillis })

    private fun ok(body: String): FetchOutcome = FetchOutcome.Ok(body)

    private fun failure(kind: FailureKind): FetchOutcome = FetchOutcome.Failure(kind)

    private val biliLivePage = """{"live_status":1,"title":"pt","uname":"pn"}"""

    // ---------------- 斗鱼 ----------------

    @Test
    fun douyuUsesPrimaryApi() {
        val transport = FakeTransport { url, _ ->
            if (url.host == "open.douyucdn.cn") {
                ok("""{"error":0,"data":{"room_status":"1","room_name":"t1","owner_name":"n1"}}""")
            } else {
                failure(FailureKind.NETWORK)
            }
        }
        val result = checker(transport).check(Platform.DOUYU, "9999", "https://www.douyu.com/9999")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("douyu-api", result.src)
        assertEquals("n1", result.owner)
        // 主路径成功就不要再打 betard（请求量最小）
        assertEquals(1, transport.records.size)
        assertEquals("open.douyucdn.cn", transport.records[0].url.host)
    }

    @Test
    fun douyuFallsBackToBetardWhenApiReportsError() {
        val transport = FakeTransport { url, _ ->
            when (url.host) {
                "open.douyucdn.cn" -> ok("""{"error":105,"data":null}""")
                "www.douyu.com" -> ok(
                    """{"error":0,"room":{"show_status":"1","room_name":"t2","nickname":"n2"}}""",
                )

                else -> failure(FailureKind.NETWORK)
            }
        }
        val result = checker(transport).check(Platform.DOUYU, "9999", "https://www.douyu.com/9999")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("douyu-betard", result.src)
        assertEquals("n2", result.owner)
        assertEquals(2, transport.records.size)
        // betard 请求必须带平台内 Referer（与浏览器行为一致）
        val betard = transport.records.first { it.url.host == "www.douyu.com" }
        assertEquals("https://www.douyu.com/", betard.referer)
    }

    @Test
    fun douyuNotFoundOnlyWhenBothPathsAgree() {
        val transport = FakeTransport { url, _ ->
            when (url.host) {
                "open.douyucdn.cn" -> ok("""{"error":105,"data":null}""")
                else -> FetchOutcome.HttpError(404)
            }
        }
        val result = checker(transport).check(Platform.DOUYU, "9999", "https://www.douyu.com/9999")
        assertEquals(LiveState.NOT_FOUND, result.state)
        assertTrue(result.error.contains("105"))
    }

    @Test
    fun douyuNonNotFoundApiErrorWithNetworkFailureIsError() {
        val transport = FakeTransport { url, _ ->
            when (url.host) {
                "open.douyucdn.cn" -> ok("""{"error":500,"data":null}""")
                else -> FetchOutcome.HttpError(500)
            }
        }
        val result = checker(transport).check(Platform.DOUYU, "9999", "https://www.douyu.com/9999")
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.HTTP_STATUS, result.failure)
    }

    @Test
    fun douyuUnparseableBothPathsIsParseError() {
        val transport = FakeTransport { _, _ -> ok("<html>garbage</html>") }
        val result = checker(transport).check(Platform.DOUYU, "9999", "https://www.douyu.com/9999")
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.PARSE, result.failure)
    }

    // ---------------- B 站 ----------------

    @Test
    fun bilibiliPageIsPrimaryAndWarmsCookiesOnce() {
        val transport = FakeTransport { url, _ ->
            when (url.host) {
                "www.bilibili.com" -> ok("<html>home</html>")
                "live.bilibili.com" -> ok(biliLivePage)
                else -> failure(FailureKind.NETWORK)
            }
        }
        val engine = checker(transport)
        val result = engine.check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("bili-page", result.src)
        assertEquals(2, transport.records.size)
        assertEquals("www.bilibili.com", transport.records[0].url.host)
        assertEquals("live.bilibili.com", transport.records[1].url.host)

        // 20 分钟内第二次检测不再重复预热
        engine.check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(1, transport.countHost("www.bilibili.com"))
    }

    @Test
    fun bilibili412TriggersRewarmAndSingleRetry() {
        var livePageCalls = 0
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                url.host == "live.bilibili.com" -> {
                    livePageCalls++
                    if (livePageCalls == 1) FetchOutcome.HttpError(412) else ok(biliLivePage)
                }

                else -> failure(FailureKind.NETWORK)
            }
        }
        val result = checker(transport).check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals(2, livePageCalls)
        // 初次预热 + 412 后强制重新预热各一次
        assertEquals(2, transport.countHost("www.bilibili.com"))
    }

    @Test
    fun bilibiliPermanent412IsReportedAsRateLimitedError() {
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                else -> FetchOutcome.HttpError(412)
            }
        }
        val result = checker(transport).check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.HTTP_STATUS, result.failure)
    }

    @Test
    fun bilibiliFallsBackToGetInfoByRoom() {
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                url.host == "live.bilibili.com" -> ok("<html>no live status</html>")
                url.encodedPath.contains("getInfoByRoom") -> ok(
                    """{"code":0,"data":{"room_info":{"live_status":1,"title":"gt"},"anchor_info":{"base_info":{"uname":"gn"}}}}""",
                )

                else -> FetchOutcome.HttpError(500)
            }
        }
        val result = checker(transport).check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("bili-getInfoByRoom", result.src)
        assertEquals("gn", result.owner)
    }

    @Test
    fun bilibiliFallsBackToRoomInitAndGetInfo() {
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                url.host == "live.bilibili.com" -> ok("<html>no live status</html>")
                url.encodedPath.contains("getInfoByRoom") -> ok("<html>broken</html>")
                url.encodedPath.contains("room_init") -> ok("""{"code":0,"data":{"room_id":"6"}}""")
                url.encodedPath.contains("get_info") -> ok(
                    """{"code":0,"data":{"live_status":1,"title":"t","uid":42,"live_time":"2026-08-15 10:00:00"}}""",
                )

                url.encodedPath.contains("getRoomInfoOld") -> ok(
                    """{"code":0,"data":{"uname":"owner","liveStatus":1}}""",
                )

                else -> FetchOutcome.HttpError(500)
            }
        }
        val result = checker(transport).check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("bili-get_info", result.src)
        assertEquals("owner", result.owner)
    }

    @Test
    fun bilibiliUidLinkUsesGetRoomInfoOld() {
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                url.encodedPath.contains("getRoomInfoOld") -> ok(
                    """{"code":0,"data":{"roomid":6,"uname":"bu","title":"ot","liveStatus":0,"roundStatus":1}}""",
                )

                else -> FetchOutcome.HttpError(500)
            }
        }
        val result = checker(transport).check(
            Platform.BILIBILI,
            "123456",
            "https://space.bilibili.com/123456/live",
        )
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("轮播中", result.sub)
        assertEquals("bili-getRoomInfoOld", result.src)
        // UID 模式不应把 uid 当房间号去请求直播间网页
        assertEquals(0, transport.countHost("live.bilibili.com"))
    }

    @Test
    fun bilibiliAllPathsFailIsHttpStatusError() {
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                else -> FetchOutcome.HttpError(500)
            }
        }
        val result = checker(transport).check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.HTTP_STATUS, result.failure)
    }

    @Test
    fun bilibiliWarmIsThrottledFor20Minutes() {
        val transport = FakeTransport { url, _ ->
            when (url.host) {
                "www.bilibili.com" -> ok("<html>home</html>")
                else -> ok(biliLivePage)
            }
        }
        val engine = checker(transport)
        engine.check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(1, transport.countHost("www.bilibili.com"))
        nowMillis += 21L * 60 * 1000
        engine.check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals(2, transport.countHost("www.bilibili.com"))
    }

    // ---------------- 虎牙 ----------------

    @Test
    fun huyaFallsBackToMobilePage() {
        val transport = FakeTransport { url, _ ->
            when (url.host) {
                "www.huya.com" -> FetchOutcome.HttpError(404)
                "m.huya.com" -> ok("""{"isOn": true, "sNick":"hn", "sRoomName":"ht"}""")
                else -> failure(FailureKind.NETWORK)
            }
        }
        val result = checker(transport).check(Platform.HUYA, "660000", "https://www.huya.com/660000")
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("huya-page", result.src)
        assertEquals(listOf("www.huya.com", "m.huya.com"), transport.records.map { it.url.host })
    }

    @Test
    fun huyaNotFoundPageStopsImmediately() {
        val transport = FakeTransport { _, _ -> ok("<html>页面不存在</html>") }
        val result = checker(transport).check(Platform.HUYA, "660000", "https://www.huya.com/660000")
        assertEquals(LiveState.NOT_FOUND, result.state)
        assertEquals(1, transport.records.size)
    }

    // ---------------- 抖音 ----------------

    @Test
    fun douyinUsesEnterApiWhenRoomIdAvailable() {
        val page = buildString {
            append("""<script>window.__ac_nonce = "0123456789abcdef";</script>""")
            append("""<script>{"roomId":"7123456789012345678"}</script>""")
            append("x".repeat(600))
        }
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "live.douyin.com" && url.encodedPath == "/123456789012345" -> ok(page)
                url.encodedPath == "/webcast/room/web/enter" -> ok(
                    """{"data":{"data":[{"status":2,"title":"dt","owner":{"nickname":"dn"}}]}}""",
                )

                else -> FetchOutcome.HttpError(500)
            }
        }
        val result = checker(transport).check(
            Platform.DOUYIN,
            "123456789012345",
            "https://live.douyin.com/123456789012345",
        )
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("douyin-enter", result.src)
        assertEquals("dn", result.owner)
        // 页面公开的 __ac_nonce 会被种到内存 CookieJar（不落盘）
        assertEquals(listOf("live.douyin.com/__ac_nonce"), transport.seededCookies)
        assertEquals(2, transport.records.size)
    }

    @Test
    fun douyinHtmlFallbackWhenNoNonce() {
        val page = """{"web_rid":"123456789012345","status":4}""" + "x".repeat(600)
        val transport = FakeTransport { _, _ -> ok(page) }
        val result = checker(transport).check(
            Platform.DOUYIN,
            "123456789012345",
            "https://live.douyin.com/123456789012345",
        )
        assertEquals(LiveState.OFFLINE, result.state)
        assertEquals("douyin-page", result.src)
    }

    @Test
    fun douyinRestrictedReturnsUnknownWithError() {
        val transport = FakeTransport { _, _ -> ok("x".repeat(800)) }
        val result = checker(transport).check(
            Platform.DOUYIN,
            "123456789012345",
            "https://live.douyin.com/123456789012345",
        )
        assertEquals(LiveState.UNKNOWN, result.state)
        assertTrue(result.error.isNotEmpty())
        assertFalse(result.failure == FailureKind.NONE)
    }

    @Test
    fun douyinRateLimitedPageIsHttpStatusError() {
        val transport = FakeTransport { _, _ -> FetchOutcome.HttpError(429) }
        val result = checker(transport).check(
            Platform.DOUYIN,
            "123456789012345",
            "https://live.douyin.com/123456789012345",
        )
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.HTTP_STATUS, result.failure)
        assertTrue(result.error.contains("限流"))
    }

    // ---------------- 快手 ----------------

    @Test
    fun kuaishouParsesPage() {
        val transport = FakeTransport { _, _ -> ok("""{"name":"kn","isLiving":true}""") }
        val result = checker(transport).check(
            Platform.KUAISHOU,
            "3xabc123",
            "https://live.kuaishou.com/u/3xabc123",
        )
        assertEquals(LiveState.LIVE, result.state)
        assertEquals("kuaishou-page", result.src)
        assertEquals("kn", result.owner)
    }

    @Test
    fun kuaishouParseFailureIsParseError() {
        val transport = FakeTransport { _, _ -> ok("y".repeat(2000)) }
        val result = checker(transport).check(
            Platform.KUAISHOU,
            "3xabc123",
            "https://live.kuaishou.com/u/3xabc123",
        )
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.PARSE, result.failure)
    }

    // ---------------- 安全：请求域名/协议不变量 ----------------

    @Test
    fun allChecksOnlyRequestWhitelistedHttpsHosts() {
        val ids = mapOf(
            Platform.DOUYU to "9999",
            Platform.HUYA to "660000",
            Platform.BILIBILI to "6",
            Platform.DOUYIN to "123456789012345",
            Platform.KUAISHOU to "3xabc123",
        )
        val urls = mapOf(
            Platform.DOUYU to "https://www.douyu.com/9999",
            Platform.HUYA to "https://www.huya.com/660000",
            Platform.BILIBILI to "https://live.bilibili.com/6",
            Platform.DOUYIN to "https://live.douyin.com/123456789012345",
            Platform.KUAISHOU to "https://live.kuaishou.com/u/3xabc123",
        )
        // 所有请求都返回“无内容”，把所有降级路径也走一遍，检查请求不会越界
        for (platform in Platform.values()) {
            val transport = FakeTransport { _, _ -> FetchOutcome.Ok("") }
            checker(transport).check(platform, ids.getValue(platform), urls.getValue(platform))
            assertTrue("${platform.key} 应至少发出一次请求", transport.records.isNotEmpty())
            for (recorded in transport.records) {
                val url = recorded.url
                assertTrue("必须 https", url.isHttps)
                assertEquals("必须标准端口", 443, url.port)
                assertTrue("不得携带 userinfo", url.username.isEmpty() && url.password.isEmpty())
                assertTrue(
                    "越界域名: ${url.host}",
                    PlatformHosts.isAllowedRequestHost(url.host),
                )
            }
        }
    }

    @Test
    fun transportPolicyFailureIsClassifiedAsPolicy() {
        val transport = FakeTransport { _, _ -> FetchOutcome.Failure(FailureKind.POLICY) }
        val result = checker(transport).check(Platform.KUAISHOU, "3xabc123", "https://live.kuaishou.com/u/3xabc123")
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.POLICY, result.failure)
    }

    @Test
    fun transportTimeoutIsClassifiedAsTimeout() {
        val transport = FakeTransport { _, _ -> FetchOutcome.Failure(FailureKind.TIMEOUT) }
        val result = checker(transport).check(Platform.HUYA, "660000", "https://www.huya.com/660000")
        assertEquals(LiveState.ERROR, result.state)
        assertEquals(FailureKind.TIMEOUT, result.failure)
        assertEquals("请求超时", result.error)
    }

    @Test
    fun checkResultTextFieldsAreSanitizedAndBounded() {
        // 平台返回超长/带控制字符的标题时，最终 CheckResult 必须被清洗
        val page = """{"live_status":1,"title":"\u0001标题\u0002","uname":"up"}"""
        val transport = FakeTransport { url, _ ->
            when {
                url.host == "www.bilibili.com" -> ok("<html>home</html>")
                else -> ok(page)
            }
        }
        val result = checker(transport).check(Platform.BILIBILI, "6", "https://live.bilibili.com/6")
        assertEquals("标题", result.title)
        assertTrue(result.title.length <= 120)
    }
}
