package com.livemonitor.app.platform

import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.platform.FetchOutcome
import com.livemonitor.app.core.platform.HttpFetcher
import com.livemonitor.app.core.platform.UrlPolicy
import com.livemonitor.app.core.platform.UrlPolicyViolation
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * HTTP 抓取层的离线行为测试（okhttp MockWebServer，不访问外网）。
 *
 * 覆盖安全/稳定性要求：
 * - 超时、非 200 状态码、重定向到非白名单 host、超大响应体被拒；
 * - 初始地址越界时**一个请求都不发出**（策略在 DNS/连接之前生效）；
 * - 重定向上限、gzip 透明解压、Referer 只在白名单域名内发送；
 * - 所有失败都转换为 [FetchOutcome]（不抛异常）。
 */
class HttpFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: HttpFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = HttpFetcher(
            baseClient = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .build(),
            policy = UrlPolicy { url ->
                if (url.host != server.hostName) throw UrlPolicyViolation()
            },
        )
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // MockWebServer 关闭失败不影响测试结论
        }
    }

    private fun serverUrl(path: String = "/page"): String = server.url(path).toString()

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun returnsBodyOn200WithDesktopUserAgent() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("hello"))
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Ok)
        assertEquals("hello", (outcome as FetchOutcome.Ok).body)

        val request = server.takeRequest()
        assertEquals(HttpFetcher.USER_AGENT, request.getHeader("User-Agent"))
        assertTrue(request.getHeader("User-Agent")!!.contains("Chrome/126.0.0.0"))
    }

    @Test
    fun non200StatusIsReportedWithoutThrowing() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR))
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.HttpError)
        assertEquals(500, (outcome as FetchOutcome.HttpError).status)
    }

    @Test
    fun http412IsReportedForBilibiliRetry() {
        server.enqueue(MockResponse().setResponseCode(412))
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.HttpError)
        assertEquals(412, (outcome as FetchOutcome.HttpError).status)
    }

    @Test
    fun readTimeoutBecomesTimeoutFailure() {
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setBody("slow")
                .setBodyDelay(2, TimeUnit.SECONDS),
        )
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Failure)
        val failure = outcome as FetchOutcome.Failure
        assertEquals("超时分类错误: ${failure.detail}", FailureKind.TIMEOUT, failure.kind)
    }

    @Test
    fun redirectToNonAllowlistedHostIsRejected() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://evil.example/steal"),
        )
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Failure)
        assertEquals(FailureKind.POLICY, (outcome as FetchOutcome.Failure).kind)
        // 只发出了初始请求；越界重定向在发起连接前就被拦下
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectWithinAllowlistedHostIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("final"))
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Ok)
        assertEquals("final", (outcome as FetchOutcome.Ok).body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun redirectLoopIsBounded() {
        repeat(HttpFetcher.MAX_REDIRECTS + 2) {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop"))
        }
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Failure)
        assertEquals(FailureKind.POLICY, (outcome as FetchOutcome.Failure).kind)
        assertTrue(server.requestCount <= HttpFetcher.MAX_REDIRECTS + 1)
    }

    @Test
    fun oversizedDeclaredBodyIsRejected() {
        val smallFetcher = HttpFetcher(
            baseClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
            policy = UrlPolicy { url -> if (url.host != server.hostName) throw UrlPolicyViolation() },
            maxBodyBytes = 1024,
        )
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("x".repeat(4096)))
        val outcome = smallFetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Failure)
        assertEquals(FailureKind.PARSE, (outcome as FetchOutcome.Failure).kind)
    }

    @Test
    fun oversizedChunkedBodyIsRejected() {
        val smallFetcher = HttpFetcher(
            baseClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
            policy = UrlPolicy { url -> if (url.host != server.hostName) throw UrlPolicyViolation() },
            maxBodyBytes = 1024,
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setChunkedBody("y".repeat(4096), 128),
        )
        val outcome = smallFetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Failure)
        assertEquals(FailureKind.PARSE, (outcome as FetchOutcome.Failure).kind)
    }

    @Test
    fun gzipBodyIsTransparentlyDecoded() {
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader("Content-Encoding", "gzip")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(Buffer().write(gzip("直播平台"))),
        )
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Ok)
        assertEquals("直播平台", (outcome as FetchOutcome.Ok).body)
    }

    @Test
    fun policyRejectsInitialUrlBeforeAnyRequest() {
        val strictFetcher = HttpFetcher(
            baseClient = OkHttpClient.Builder().build(),
            policy = UrlPolicy { throw UrlPolicyViolation() },
        )
        val outcome = strictFetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Failure)
        assertEquals(FailureKind.POLICY, (outcome as FetchOutcome.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidUrlStringIsRejectedWithoutNetwork() {
        val outcome = fetcher.get("not a url")
        assertTrue(outcome is FetchOutcome.Failure)
        assertEquals(FailureKind.POLICY, (outcome as FetchOutcome.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refererOnlySentForAllowlistedHost() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("a"))
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("b"))

        // 白名单内的 Referer：发送
        fetcher.get(serverUrl("/one"), referer = serverUrl("/ref"))
        val first = server.takeRequest()
        assertTrue(first.getHeader("Referer")?.contains(server.hostName) == true)

        // 越界 Referer：静默丢弃，不影响请求本身
        fetcher.get(serverUrl("/two"), referer = "https://evil.example/")
        val second = server.takeRequest()
        assertFalse(second.getHeader("Referer")?.contains("evil") == true)
    }

    @Test
    fun emptyBodyIsOk() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(""))
        val outcome = fetcher.get(serverUrl())
        assertTrue(outcome is FetchOutcome.Ok)
        assertEquals("", (outcome as FetchOutcome.Ok).body)
    }
}
