package com.livemonitor.app.jump

import com.livemonitor.app.core.jump.JumpTargetSet
import com.livemonitor.app.core.jump.JumpTargets
import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.platform.PlatformHosts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * “进入直播间”跳转目标（[JumpTargets]）的纯 JVM 单元测试（不联网、不依赖 Android）。
 *
 * 覆盖：
 * - 5 平台包名固定常量；
 * - [JumpTargets.of] 对非法 roomId、http/file/javascript、任意域名、跨平台 URL 返回 null；
 * - 合法输入产出的 webUrl 只 https + 平台白名单 host，deep link 全部含 roomId 且 scheme 预期内；
 * - 不存在用户可控 host 拼接；
 * - [JumpTargets.isSafeOpenableUrl] 的浏览器 URL 白名单；
 * - **下载/应用市场能力已下架**：不再有下载字段/API，官方下载页 URL 不再被 openWeb 接受。
 */
class JumpTargetsTest {

    private val expectedPackages = mapOf(
        Platform.DOUYU to "air.tv.douyu.android",
        Platform.HUYA to "com.duowan.kiwi",
        Platform.BILIBILI to "tv.danmaku.bili",
        Platform.DOUYIN to "com.ss.android.ugc.aweme",
        Platform.KUAISHOU to "com.smile.gifmaker",
    )

    private val sampleIds = mapOf(
        Platform.DOUYU to "9999",
        Platform.HUYA to "660000",
        Platform.BILIBILI to "6",
        Platform.DOUYIN to "123456789012345",
        Platform.KUAISHOU to "3xabc123",
    )

    private val sampleUrls = mapOf(
        Platform.DOUYU to "https://www.douyu.com/9999",
        Platform.HUYA to "https://www.huya.com/660000",
        Platform.BILIBILI to "https://live.bilibili.com/6",
        Platform.DOUYIN to "https://live.douyin.com/123456789012345",
        Platform.KUAISHOU to "https://live.kuaishou.com/u/3xabc123",
    )

    private val expectedSchemes = mapOf(
        Platform.DOUYU to setOf("https", "douyu"),
        Platform.HUYA to setOf("https", "huya"),
        Platform.BILIBILI to setOf("https", "bilibili"),
        Platform.DOUYIN to setOf("https", "snssdk1128"),
        Platform.KUAISHOU to setOf("https", "kwai"),
    )

    private fun target(platform: Platform): JumpTargetSet {
        val result = JumpTargets.of(platform, sampleIds.getValue(platform), sampleUrls.getValue(platform))
        assertNotNull("${platform.key} 应能构造跳转目标", result)
        return result!!
    }

    private fun schemeOf(link: String): String = link.substringBefore(':').lowercase()

    // ---------------- 固定常量 ----------------

    @Test
    fun appPackagesAreExpectedConstants() {
        for ((platform, packageName) in expectedPackages) {
            assertEquals(packageName, JumpTargets.appPackage(platform))
        }
        assertEquals(5, expectedPackages.size)
    }

    // ---------------- of() 正例 ----------------

    @Test
    fun ofBuildsTargetsForAllPlatforms() {
        for (platform in Platform.entries) {
            val target = target(platform)
            assertEquals(platform, target.platform)
            assertEquals(expectedPackages.getValue(platform), target.appPackage)
            assertEquals(sampleUrls.getValue(platform), target.webUrl)
            assertTrue("deep link 候选不能为空", target.appDeepLinks.isNotEmpty())
            assertTrue("webUrl 必须包含在候选里", target.appDeepLinks.contains(target.webUrl))
        }
    }

    @Test
    fun webUrlIsFirstCandidate() {
        // 降级顺序：webUrl + setPackage(App) 优先（App Links 最可靠），随后才是 scheme 候选
        for (platform in Platform.entries) {
            val target = target(platform)
            assertEquals("首个候选应为 webUrl", target.webUrl, target.appDeepLinks[0])
        }
    }

    @Test
    fun deepLinksContainRoomIdAndExpectedScheme() {
        for (platform in Platform.entries) {
            val roomId = sampleIds.getValue(platform)
            val target = target(platform)
            val allowedSchemes = expectedSchemes.getValue(platform)
            for (link in target.appDeepLinks) {
                assertTrue("deep link 必须包含 roomId: $link", link.contains(roomId))
                assertTrue("非预期 scheme: $link", schemeOf(link) in allowedSchemes)
                assertTrue("deep link 不能含控制字符", link.none { it.code < 0x20 || it.code == 0x7f })
                assertTrue("deep link 过长", link.length <= 512)
            }
        }
    }

    @Test
    fun webUrlIsHttpsAndWhitelistedHost() {
        for (platform in Platform.entries) {
            val webUrl = target(platform).webUrl
            val parsed = webUrl.toHttpUrlOrNull()
            assertNotNull(parsed)
            assertTrue("webUrl 必须 https", parsed!!.isHttps)
            assertEquals(443, parsed.port)
            assertTrue("webUrl host 必须在平台白名单", PlatformHosts.isAllowedStoredHost(parsed.host))
            assertTrue(parsed.username.isEmpty() && parsed.password.isEmpty())
        }
    }

    @Test
    fun ofFallsBackToCanonicalUrlWhenStoredUrlBlank() {
        for (platform in Platform.entries) {
            val id = sampleIds.getValue(platform)
            val canonical = PlatformApi.buildUrl(platform, id)
            assertNotNull(canonical)
            for (stored in listOf(null, "", "   ")) {
                val target = JumpTargets.of(platform, id, stored)
                assertNotNull("空存储 URL 应按 id 重建（$stored）", target)
                assertEquals(canonical, target!!.webUrl)
            }
        }
    }

    @Test
    fun storedUrlQueryAndFragmentAreDropped() {
        val target = JumpTargets.of(
            Platform.DOUYU,
            "9999",
            "https://www.douyu.com/9999/?from=share#top",
        )
        assertNotNull(target)
        assertEquals("https://www.douyu.com/9999", target!!.webUrl)
        assertFalse(target.webUrl.contains("from=share"))
    }

    @Test
    fun bilibiliSpaceUidOnlyUsesWebUrl() {
        // UID 形态不能套 bilibili://live/{uid}（该 scheme 的 id 是房间号，可能打开别人的房间）
        val target = JumpTargets.of(
            Platform.BILIBILI,
            "123456",
            "https://space.bilibili.com/123456/live",
        )
        assertNotNull(target)
        assertEquals("https://space.bilibili.com/123456/live", target!!.webUrl)
        assertEquals(listOf(target.webUrl), target.appDeepLinks)
    }

    @Test
    fun deepLinksAreDistinct() {
        for (platform in Platform.entries) {
            val links = target(platform).appDeepLinks
            assertEquals(links.size, links.toSet().size)
        }
    }

    // ---------------- of() 反例：非法房间号 ----------------

    @Test
    fun ofRejectsIllegalRoomId() {
        val illegalIds = listOf(
            "",
            " ",
            "bad id",
            "a/b",
            "../x",
            "abc?x=1",
            "javascript:alert(1)",
            "中文房间号",
            "a".repeat(65),
            "9999\n",
        )
        for (id in illegalIds) {
            assertNull("非法 roomId 应返回 null", JumpTargets.of(Platform.DOUYU, id, "https://www.douyu.com/9999"))
        }
    }

    // ---------------- of() 反例：不可信存储 URL ----------------

    @Test
    fun ofRejectsUntrustedStoredUrls() {
        val badUrls = listOf(
            "http://www.douyu.com/9999",
            "file:///C:/Windows/notepad.exe",
            "javascript:alert(1)",
            "data:text/html,x",
            "https://evil.example/9999",
            "https://www.douyu.com.evil.example/9999",
            "https://127.0.0.1/9999",
            "https://www.douyu.com:8443/9999",
            "https://user:pass@www.douyu.com/9999",
            "https://www.douyu.com/8888", // id 不匹配
            "https://www.douyu.com/9999/extra", // 多段路径
            "https://www.douyu.com/9999\nX-Injected: 1",
            "https://www.douyu.com/9999?" + "a".repeat(600), // 超长
        )
        for (url in badUrls) {
            assertNull("不可信 URL 应返回 null", JumpTargets.of(Platform.DOUYU, "9999", url))
        }
    }

    @Test
    fun ofRejectsCrossPlatformStoredUrl() {
        assertNull(JumpTargets.of(Platform.HUYA, "660000", "https://www.douyu.com/660000"))
        assertNull(JumpTargets.of(Platform.DOUYU, "9999", "https://live.bilibili.com/9999"))
        assertNull(JumpTargets.of(Platform.KUAISHOU, "3xabc123", "https://www.huya.com/3xabc123"))
        assertNull(JumpTargets.of(Platform.DOUYIN, "123456789012345", "https://live.kuaishou.com/u/123456789012345"))
    }

    @Test
    fun ofRejectsBilibiliSpaceUidMismatch() {
        assertNull(
            JumpTargets.of(Platform.BILIBILI, "123456", "https://space.bilibili.com/999/live"),
        )
        assertNull(
            JumpTargets.of(Platform.BILIBILI, "123456", "https://space.bilibili.com/123456/video"),
        )
    }

    // ---------------- 无用户可控 host 拼接 ----------------

    @Test
    fun noUserControlledHostInjection() {
        // 恶意 roomId 无法拼出任何 host（ID 规则直接拒绝）
        val injectionIds = listOf(
            "9999.evil.example",
            "9999@evil.example",
            "9999/../evil.example",
            "9999#@evil.example",
            "9999?x=evil.example",
            "//evil.example",
        )
        for (id in injectionIds) {
            assertNull(
                "拼接注入应被拒绝",
                JumpTargets.of(Platform.DOUYU, id, null),
            )
        }

        // 合法目标里：https 候选的 host 只能来自平台房间页白名单
        for (platform in Platform.entries) {
            for (link in target(platform).appDeepLinks) {
                if (schemeOf(link) != "https") continue
                val host = link.toHttpUrlOrNull()?.host
                assertNotNull(host)
                assertTrue("https 候选 host 越界: $host", PlatformHosts.isAllowedStoredHost(host))
            }
        }
    }

    // ---------------- 浏览器 URL 白名单 ----------------

    @Test
    fun isSafeOpenableUrlAcceptsPlatformRoomPages() {
        assertTrue(JumpTargets.isSafeOpenableUrl("https://www.douyu.com/9999"))
        assertTrue(JumpTargets.isSafeOpenableUrl("https://www.huya.com/660000"))
        assertTrue(JumpTargets.isSafeOpenableUrl("https://live.bilibili.com/6"))
        assertTrue(JumpTargets.isSafeOpenableUrl("https://space.bilibili.com/123456/live"))
        assertTrue(JumpTargets.isSafeOpenableUrl("https://live.douyin.com/123456789012345"))
        assertTrue(JumpTargets.isSafeOpenableUrl("https://live.kuaishou.com/u/3xabc123"))
        // 生成的目标全部可以安全打开
        for (platform in Platform.entries) {
            assertTrue(JumpTargets.isSafeOpenableUrl(target(platform).webUrl))
        }
    }

    @Test
    fun isSafeOpenableUrlRejectsDangerousUrls() {
        val badUrls = listOf(
            null,
            "",
            "   ",
            "not a url",
            "http://www.douyu.com/9999",
            "http://live.bilibili.com/6",
            "file:///C:/Windows/notepad.exe",
            "javascript:alert(1)",
            "data:text/html,x",
            "https://evil.example/9999",
            "https://www.douyu.com.evil.example/9999",
            "https://127.0.0.1/9999",
            "https://localhost/9999",
            "https://www.douyu.com:8443/9999",
            "https://user:pass@www.douyu.com/9999",
            "https://www.douyu.com/9999\nX-Injected: 1",
            "https://www.douyu.com/" + "9".repeat(600),
        )
        for (url in badUrls) {
            assertFalse("危险 URL 必须拒绝: $url", JumpTargets.isSafeOpenableUrl(url))
        }
    }

    @Test
    fun isSafeOpenableUrlRejectsDownloadPages() {
        // 下载功能已下架：官方下载页域名不再属于可打开白名单
        val formerDownloadPages = listOf(
            "https://app.bilibili.com/",
            "https://www.douyin.com/downloadpage",
            "https://www.kuaishou.com/",
        )
        for (url in formerDownloadPages) {
            assertFalse("下载页不应再被 openWeb 接受: $url", JumpTargets.isSafeOpenableUrl(url))
        }
    }

    // ---------------- 下载/市场能力已彻底移除 ----------------

    @Test
    fun noDownloadOrMarketApiRemains() {
        val forbiddenWords = listOf("vendor", "download", "market")
        fun forbidden(name: String): Boolean = forbiddenWords.any { name.contains(it, ignoreCase = true) }

        // JumpTargetSet 只保留 platform/appPackage/appDeepLinks/webUrl 四个字段
        //（排除 Kotlin/Compose 编译器生成的合成字段，如 $stable）
        val fieldNames = JumpTargetSet::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(setOf("platform", "appPackage", "appDeepLinks", "webUrl"), fieldNames)

        // 跳转模块不再暴露/持有任何下载、官网下载页、应用市场相关 API/常量
        assertTrue(
            "JumpTargets 仍有下载/市场相关方法",
            JumpTargets::class.java.declaredMethods.none { forbidden(it.name) },
        )
        assertTrue(
            "JumpTargets 仍有下载/市场相关字段",
            JumpTargets::class.java.declaredFields.none { forbidden(it.name) },
        )
        assertTrue(
            "JumpTargetSet 仍有下载/市场相关字段",
            JumpTargetSet::class.java.declaredFields.none { forbidden(it.name) },
        )
    }
}
