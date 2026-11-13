package com.livemonitor.app.core.platform

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * TLS 证书固定策略的配置级测试。
 *
 * 说明：这里不发起真实网络请求（避免 CI 依赖外网与证书轮换），而是校验：
 * - 所有允许请求的平台 host 都在固定覆盖范围内，且未知 host 不被固定；
 * - 每个 host 的 pin 数量/格式/解码长度合法；
 * - 开启固定时 OkHttpClient 带 CertificatePinner，关闭时不带。
 * 生产环境 pin 的真实性在发布前由人工用 `openssl s_client -showcerts` 核验（见安全文档）。
 */
class TlsPolicyTest {

    @Test
    fun `every allowed request host is covered by pinning`() {
        val requestHosts = PlatformHosts.requestHosts.values.flatten().toSet()
        val uncovered = requestHosts.filterNot { TlsPolicy.isCoveredHost(it) }
        assertTrue("未固定的平台 host: $uncovered", uncovered.isEmpty())
        assertEquals(requestHosts, TlsPolicy.coveredHosts)
    }

    @Test
    fun `unknown hosts are not pinned`() {
        assertFalse(TlsPolicy.isCoveredHost("example.com"))
        assertFalse(TlsPolicy.isCoveredHost("bilibili.com.evil.example"))
        assertFalse(TlsPolicy.isCoveredHost(null))
    }

    @Test
    fun `each host has at least two well-formed root or intermediate pins`() {
        for (host in TlsPolicy.coveredHosts) {
            val pins = TlsPolicy.pinsForHost(host)
            assertTrue("$host 至少需要 2 个 pin（轮换/兼容）", pins.size >= 2)
            for (pin in pins) {
                assertTrue("非法 pin 前缀: $pin", pin.startsWith("sha256/"))
                val decoded = Base64.getDecoder().decode(pin.removePrefix("sha256/"))
                assertEquals("SPKI SHA-256 必须是 32 字节: $pin", 32, decoded.size)
            }
        }
    }

    @Test
    fun `certificate pinner matches all platform hosts`() {
        val pinner = TlsPolicy.certificatePinner()
        for (host in TlsPolicy.coveredHosts) {
            assertTrue("CertificatePinner 未配置 host: $host", pinner.findMatchingPins(host).isNotEmpty())
        }
        assertTrue(pinner.findMatchingPins("localhost").isEmpty())
    }

    @Test
    fun `pinned client has certificate pinner and unpinned client does not`() {
        val pinnedClient: OkHttpClient = HttpFetcher.defaultClient(pinningEnabled = true)
        assertNotNull(pinnedClient.certificatePinner)
        assertTrue(pinnedClient.certificatePinner.findMatchingPins("live.bilibili.com").isNotEmpty())

        val unpinnedClient: OkHttpClient = HttpFetcher.defaultClient(pinningEnabled = false)
        assertTrue(unpinnedClient.certificatePinner.findMatchingPins("live.bilibili.com").isEmpty())
    }
}
