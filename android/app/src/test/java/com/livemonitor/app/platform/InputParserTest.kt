package com.livemonitor.app.platform

import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.platform.PlatformApi.ParseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入解析（粘贴链接/房间号 → 平台 + 规范 id + 规范 URL）的离线单元测试。
 *
 * 断言样例移植自桌面版 test/TestProgram.cs，并补充了 Android 端的安全反例
 * （短链接、搜索页、非 http(s) 协议、控制字符、超长输入、userinfo 等）。
 */
class InputParserTest {

    private fun parsed(input: String, hint: Platform? = null): PlatformApi.ParsedInput {
        val outcome = PlatformApi.parseInput(input, hint)
        // 断言信息不打印输入/URL 内容，避免测试输出泄露地址
        assertTrue("预期解析成功（本机解析）", outcome is ParseOutcome.Ok)
        return (outcome as ParseOutcome.Ok).input
    }

    private fun failure(input: String, hint: Platform? = null): String {
        val outcome = PlatformApi.parseInput(input, hint)
        assertTrue("预期解析失败（本机解析）", outcome is ParseOutcome.Failure)
        return (outcome as ParseOutcome.Failure).message
    }

    private fun assertCanonical(input: PlatformApi.ParsedInput) {
        assertTrue("规范 URL 必须是 https", input.url.startsWith("https://"))
        assertTrue("规范 URL 不能包含空白/控制字符", input.url.none { it.isWhitespace() || it.code < 0x20 })
        assertTrue("规范 URL 过长", input.url.length <= 512)
        assertTrue("id 必须是规范字符集", input.id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    // ---------------- 各平台正例 ----------------

    @Test
    fun parseDouyuUrl() {
        val result = parsed("https://www.douyu.com/9999")
        assertEquals(Platform.DOUYU, result.platform)
        assertEquals("9999", result.id)
        assertEquals("https://www.douyu.com/9999", result.url)
        assertCanonical(result)
    }

    @Test
    fun parseDouyuUrlWithoutScheme() {
        val result = parsed("www.douyu.com/9999")
        assertEquals(Platform.DOUYU, result.platform)
        assertEquals("9999", result.id)
        assertCanonical(result)
    }

    @Test
    fun parseDouyuHttpUrlUpgradesToHttps() {
        val result = parsed("http://www.douyu.com/9999")
        assertEquals(Platform.DOUYU, result.platform)
        assertEquals("https://www.douyu.com/9999", result.url)
    }

    @Test
    fun parseDouyuBareRoomNumber() {
        val result = parsed("9999", Platform.DOUYU)
        assertEquals(Platform.DOUYU, result.platform)
        assertEquals("9999", result.id)
    }

    @Test
    fun parseHuyaUrl() {
        val result = parsed("https://www.huya.com/660000")
        assertEquals(Platform.HUYA, result.platform)
        assertEquals("660000", result.id)
        assertEquals("https://www.huya.com/660000", result.url)
    }

    @Test
    fun parseHuyaBareRoomNumber() {
        val result = parsed("660000", Platform.HUYA)
        assertEquals(Platform.HUYA, result.platform)
        assertEquals("660000", result.id)
    }

    @Test
    fun parseBilibiliRoomUrl() {
        val result = parsed("https://live.bilibili.com/12345")
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("12345", result.id)
        assertEquals("https://live.bilibili.com/12345", result.url)
    }

    @Test
    fun parseBilibiliShortRoomId() {
        val result = parsed("https://live.bilibili.com/6")
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("6", result.id)
    }

    @Test
    fun parseBilibiliUrlWithQuery() {
        val result = parsed("https://live.bilibili.com/12345?broadcast_type=0")
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("12345", result.id)
        assertEquals("https://live.bilibili.com/12345", result.url)
    }

    @Test
    fun parseBilibiliSpaceUid() {
        val result = parsed("https://space.bilibili.com/123456")
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("123456", result.id)
        assertEquals("https://space.bilibili.com/123456/live", result.url)
    }

    @Test
    fun parseBilibiliSpaceUidWithoutScheme() {
        val result = parsed("space.bilibili.com/123456")
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("123456", result.id)
        assertTrue(result.url.contains("space.bilibili.com"))
    }

    @Test
    fun parseBilibiliSpaceUidLivePage() {
        val result = parsed("https://space.bilibili.com/123456/live")
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("123456", result.id)
        assertEquals("https://space.bilibili.com/123456/live", result.url)
    }

    @Test
    fun parseDouyinFullUrl() {
        val result = parsed("https://live.douyin.com/123456789012345")
        assertEquals(Platform.DOUYIN, result.platform)
        assertEquals("123456789012345", result.id)
        assertEquals("https://live.douyin.com/123456789012345", result.url)
    }

    @Test
    fun parseDouyinUrlWithQuery() {
        val result = parsed("https://live.douyin.com/123456789012345?enter_from=web_live")
        assertEquals(Platform.DOUYIN, result.platform)
        assertEquals("123456789012345", result.id)
    }

    @Test
    fun parseKuaishouUrl() {
        val result = parsed("https://live.kuaishou.com/u/3xabc123")
        assertEquals(Platform.KUAISHOU, result.platform)
        assertEquals("3xabc123", result.id)
        assertEquals("https://live.kuaishou.com/u/3xabc123", result.url)
    }

    @Test
    fun parseKuaishouProfileUrl() {
        val result = parsed("https://live.kuaishou.com/profile/3xabc123")
        assertEquals(Platform.KUAISHOU, result.platform)
        assertEquals("3xabc123", result.id)
        assertEquals("https://live.kuaishou.com/u/3xabc123", result.url)
    }

    @Test
    fun parseKuaishouBareId() {
        val result = parsed("3xabc123", Platform.KUAISHOU)
        assertEquals(Platform.KUAISHOU, result.platform)
        assertEquals("3xabc123", result.id)
    }

    @Test
    fun hintSelectsPlatform() {
        // 指定平台时按指定平台解析（纯数字输入无法自动识别平台）
        val result = parsed("777", Platform.BILIBILI)
        assertEquals(Platform.BILIBILI, result.platform)
        assertEquals("777", result.id)
        assertEquals("https://live.bilibili.com/777", result.url)
    }

    @Test
    fun hintUsedForNonPlatformLink() {
        // 链接 host 不是平台域名时，仍按用户选择的平台解析（与桌面版一致）
        val result = parsed("https://example.com/stream/9999", Platform.DOUYU)
        assertEquals(Platform.DOUYU, result.platform)
        assertEquals("9999", result.id)
        assertEquals("https://www.douyu.com/9999", result.url)
    }

    @Test
    fun linkPlatformOverridesSelectedHint() {
        // 链接自带平台域名时以链接为准，避免“选错平台 → 监测错房间”
        val result = parsed("https://www.douyu.com/9999", Platform.BILIBILI)
        assertEquals(Platform.DOUYU, result.platform)
        assertEquals("https://www.douyu.com/9999", result.url)
    }

    // ---------------- 反例：平台/格式 ----------------

    @Test
    fun autoDetectBareNumberRejected() {
        // 桌面版自测同款断言：没有平台提示时，裸数字不能猜平台
        val message = failure("12345")
        assertTrue(message.contains("无法识别平台"))
    }

    @Test
    fun autoDetectUnknownHostRejected() {
        val message = failure("https://example.com/9999")
        assertTrue(message.contains("无法识别平台"))
    }

    @Test
    fun emptyInputRejected() {
        assertTrue(failure("").contains("请输入"))
        assertTrue(failure("   ").contains("请输入"))
    }

    @Test
    fun douyinShortLinkRejected() {
        val message = failure("https://v.douyin.com/abcdefg/")
        assertTrue(message.contains("短链接"))
        assertTrue(message.contains("完整地址"))
    }

    @Test
    fun douyinNonLiveHostRejected() {
        val message = failure("https://www.douyin.com/video/1234567890")
        assertTrue(message.contains("live.douyin.com"))
    }

    @Test
    fun bilibiliSearchPageRejected() {
        val message = failure("https://www.bilibili.com/search?keyword=abc")
        assertTrue(message.contains("具体直播间"))
    }

    @Test
    fun bilibiliLiveSearchPageRejected() {
        val message = failure("https://live.bilibili.com/search")
        assertTrue(message.contains("具体直播间"))
    }

    @Test
    fun douyinSearchPageRejected() {
        val message = failure("https://live.douyin.com/search/abc")
        assertTrue(message.contains("具体直播间"))
    }

    @Test
    fun douyuUrlWithoutDigitsRejected() {
        assertTrue(failure("https://www.douyu.com/").contains("数字ID"))
        assertTrue(failure("https://www.douyu.com/abc").contains("数字ID"))
    }

    @Test
    fun douyuHintRejectsNonNumericId() {
        assertTrue(failure("abc", Platform.DOUYU).contains("数字"))
    }

    @Test
    fun tooLongInputRejected() {
        val longUrl = "https://www.douyu.com/" + "9".repeat(3000)
        assertTrue(failure(longUrl).contains("过长"))
    }

    // ---------------- 反例：协议/注入 ----------------

    @Test
    fun fileProtocolRejected() {
        assertTrue(failure("file:///C:/Windows/notepad.exe").contains("http/https"))
    }

    @Test
    fun javascriptProtocolRejected() {
        assertTrue(failure("javascript:alert(1)").contains("http/https"))
    }

    @Test
    fun ftpProtocolRejected() {
        assertTrue(failure("ftp://www.douyu.com/9999").contains("http/https"))
    }

    @Test
    fun controlCharsRejected() {
        assertTrue(failure("https://www.douyu.com/99\u000099").contains("不允许的字符"))
        assertTrue(failure("https://www.douyu.com/9999\r\nX-Injected: 1").contains("不允许的字符"))
    }

    @Test
    fun userInfoInUrlRejected() {
        assertTrue(failure("https://user:pass@www.douyu.com/9999").contains("认证信息"))
    }

    @Test
    fun kuaishouLinkWithoutIdRejected() {
        // 链接里只有 /u/ 而没有合法 ID
        val outcome = PlatformApi.parseInput("https://live.kuaishou.com/u/")
        assertTrue(outcome is ParseOutcome.Failure)
    }
}
