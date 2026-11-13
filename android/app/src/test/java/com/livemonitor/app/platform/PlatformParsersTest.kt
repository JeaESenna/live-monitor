package com.livemonitor.app.platform

import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.platform.BilibiliParser
import com.livemonitor.app.core.platform.DouyinParser
import com.livemonitor.app.core.platform.DouyuParser
import com.livemonitor.app.core.platform.HuyaParser
import com.livemonitor.app.core.platform.KuaishouParser
import com.livemonitor.app.core.platform.PlatformText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 各平台解析函数的离线单元测试（不联网）。
 *
 * 断言样例移植自桌面版 test/TestProgram.cs 的解析自测，并额外覆盖：
 * 损坏/截断 JSON、字段类型异常、控制字符、超长文本、Unicode 转义、时间字段。
 */
class PlatformParsersTest {

    private fun live(result: CheckResult?): CheckResult {
        assertNotNull("解析结果不应为 null", result)
        val checked = result!!
        assertEquals(LiveState.LIVE, checked.state)
        return checked
    }

    private fun offline(result: CheckResult?): CheckResult {
        assertNotNull("解析结果不应为 null", result)
        val checked = result!!
        assertEquals(LiveState.OFFLINE, checked.state)
        return checked
    }

    private fun notFound(result: CheckResult?): CheckResult {
        assertNotNull("解析结果不应为 null", result)
        val checked = result!!
        assertEquals(LiveState.NOT_FOUND, checked.state)
        return checked
    }

    private fun epochOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // ---------------- 斗鱼 ----------------

    @Test
    fun douyuLive() {
        val result = live(
            DouyuParser.parseRoomApi(
                """{"error":0,"data":{"room_id":"9999","room_status":"1","room_name":"t1","owner_name":"n1"}}""",
            ),
        )
        assertEquals("t1", result.title)
        assertEquals("n1", result.owner)
        assertNull(result.liveStartAt)
    }

    @Test
    fun douyuOffline() {
        val result = offline(
            DouyuParser.parseRoomApi(
                """{"error":0,"data":{"room_status":"2","room_name":"","owner_name":""}}""",
            ),
        )
        assertNull(result.liveStartAt)
    }

    @Test
    fun douyuNotFound() {
        val result = notFound(DouyuParser.parseRoomApi("""{"error":105,"data":null}"""))
        assertTrue(result.error.contains("105"))
        assertEquals(105L, DouyuParser.apiErrorCode("""{"error":105,"data":null}""") ?: -1L)
    }

    @Test
    fun douyuBetardLive() {
        val result = live(
            DouyuParser.parseBetard(
                """{"error":0,"room":{"show_status":"1","room_name":"t2","nickname":"n2"}}""",
            ),
        )
        assertEquals("n2", result.owner)
        assertEquals("t2", result.title)
    }

    @Test
    fun douyuAreaAndStartTime() {
        val result = live(
            DouyuParser.parseRoomApi(
                """{"error":0,"data":{"room_status":"1","room_name":"t","owner_name":"n","cate_name":"DOTA2","start_time":"2026-08-15 09:05:00"}}""",
            ),
        )
        assertEquals("DOTA2", result.area)
        assertEquals(epochOf(2026, 8, 15, 9, 5, 0), result.liveStartAt ?: -1L)
    }

    @Test
    fun douyuOfflineHasNoStartTime() {
        val result = offline(
            DouyuParser.parseRoomApi(
                """{"error":0,"data":{"room_status":"2","room_name":"","owner_name":"","start_time":"2026-08-15 09:05:00"}}""",
            ),
        )
        assertNull(result.liveStartAt)
    }

    @Test
    fun douyuMalformedInputsAreSafe() {
        assertNull(DouyuParser.parseRoomApi(null))
        assertNull(DouyuParser.parseRoomApi(""))
        assertNull(DouyuParser.parseRoomApi("not json"))
        assertNull(DouyuParser.parseRoomApi("""{"error":0,"data":"unexpected"}"""))
        assertNull(DouyuParser.parseRoomApi("""{"error":0}"""))
        assertNull(DouyuParser.parseRoomApi("""{"error":0,"data":"""))
        assertNull(DouyuParser.parseBetard("""{"error":0,"room":[]}"""))
        assertNull(DouyuParser.apiErrorCode("oops"))
    }

    @Test
    fun douyuAcceptsNumericFields() {
        val result = live(
            DouyuParser.parseRoomApi(
                """{"error":0,"data":{"room_status":1,"room_name":123,"owner_name":456}}""",
            ),
        )
        assertEquals("123", result.title)
        assertEquals("456", result.owner)
    }

    // ---------------- B 站 ----------------

    @Test
    fun bilibiliGetInfoLive() {
        val result = live(
            BilibiliParser.parseGetInfo(
                """{"code":0,"data":{"live_status":1,"title":"bt","uid":1,"room_id":6}}""",
            ),
        )
        assertEquals("bt", result.title)
    }

    @Test
    fun bilibiliGetInfoRoundPlay() {
        val result = live(
            BilibiliParser.parseGetInfo(
                """{"code":0,"data":{"live_status":2,"title":"","uid":1,"room_id":6}}""",
            ),
        )
        assertEquals("轮播中", result.sub)
    }

    @Test
    fun bilibiliGetInfoOffline() {
        offline(
            BilibiliParser.parseGetInfo(
                """{"code":0,"data":{"live_status":0,"title":"","uid":1,"room_id":6}}""",
            ),
        )
    }

    @Test
    fun bilibiliGetInfoNotFound() {
        notFound(BilibiliParser.parseGetInfo("""{"code":-400,"data":null}"""))
    }

    @Test
    fun bilibiliGetRoomInfoOldRoundPlay() {
        val result = live(
            BilibiliParser.parseGetRoomInfoOld(
                """{"code":0,"data":{"roomid":6,"uname":"bu","title":"ot","liveStatus":0,"roundStatus":1}}""",
            ),
        )
        assertEquals("bu", result.owner)
        assertEquals("轮播中", result.sub)
    }

    @Test
    fun bilibiliGetInfoByRoomLiveAndOwner() {
        val result = live(
            BilibiliParser.parseGetInfoByRoom(
                """{"code":0,"data":{"room_info":{"live_status":1,"title":"gt"},"anchor_info":{"base_info":{"uname":"gn"}}}}""",
            ),
        )
        assertEquals("gn", result.owner)
        assertEquals("gt", result.title)
    }

    @Test
    fun bilibiliGetInfoByRoomRoundPlayAndOffline() {
        val round = live(
            BilibiliParser.parseGetInfoByRoom(
                """{"code":0,"data":{"room_info":{"live_status":2,"title":""},"anchor_info":{"base_info":{"uname":"gn"}}}}""",
            ),
        )
        assertEquals("轮播中", round.sub)

        val offlineResult = offline(
            BilibiliParser.parseGetInfoByRoom(
                """{"code":0,"data":{"room_info":{"live_status":0},"anchor_info":{"base_info":{"uname":"gn"}}}}""",
            ),
        )
        assertEquals("gn", offlineResult.owner)

        assertNull(BilibiliParser.parseGetInfoByRoom("""{"code":-400,"data":null}"""))
        assertNull(BilibiliParser.parseGetInfoByRoom("""{"code":0,"data":null}"""))
        assertNull(BilibiliParser.parseGetInfoByRoom("broken"))
    }

    @Test
    fun bilibiliLivePageBasic() {
        val liveResult = live(BilibiliParser.parseLivePage("""{"live_status":1,"title":"pt","uname":"pn"}"""))
        assertEquals("pn", liveResult.owner)
        assertEquals("pt", liveResult.title)

        offline(BilibiliParser.parseLivePage("""{"live_status":0,"uname":"pn"}"""))

        val round = live(BilibiliParser.parseLivePage("""{"live_status":2,"title":"pt","uname":"pn"}"""))
        assertEquals("轮播中", round.sub)

        assertNull(BilibiliParser.parseLivePage("<html>nothing here</html>"))
        assertNull(BilibiliParser.parseLivePage(null))
        assertNull(BilibiliParser.parseLivePage(""))
    }

    @Test
    fun bilibiliLivePageAreaPrefersSubArea() {
        val page = live(
            BilibiliParser.parseLivePage(
                """{"live_status":1,"title":"pt","uname":"pn","parent_area_name":"网游","area_name":"最终幻想14","live_start_time":1793446476}""",
            ),
        )
        assertEquals("最终幻想14", page.area)
        assertEquals(1793446476000L, page.liveStartAt ?: -1L)

        val parentOnly = live(
            BilibiliParser.parseLivePage(
                """{"live_status":1,"uname":"pn","parent_area_name":"网游"}""",
            ),
        )
        assertEquals("网游", parentOnly.area)
    }

    @Test
    fun bilibiliLivePageOldStyleTime() {
        val page = live(
            BilibiliParser.parseLivePage(
                """{"live_status":1,"uname":"pn","area_name":"虚拟主播","live_time":"2026-10-31 09:15:13"}""",
            ),
        )
        assertEquals("虚拟主播", page.area)
        assertEquals(epochOf(2026, 10, 31, 9, 15, 13), page.liveStartAt ?: -1L)
    }

    @Test
    fun bilibiliLivePageCamelCaseTime() {
        val page = live(
            BilibiliParser.parseLivePage(
                """{"live_status":1,"uname":"pn","liveTime":"2026-08-15 14:30:00"}""",
            ),
        )
        assertEquals(epochOf(2026, 8, 15, 14, 30, 0), page.liveStartAt ?: -1L)
    }

    @Test
    fun bilibiliGetInfoByRoomAreaAndTime() {
        val result = live(
            BilibiliParser.parseGetInfoByRoom(
                """{"code":0,"data":{"room_info":{"live_status":1,"title":"gt","live_start_time":1786793400,"parent_area_name":"单机游戏","area_name":"深空当铺"}}}""",
            ),
        )
        assertEquals("深空当铺", result.area)
        val startAt = result.liveStartAt ?: -1L
        assertEquals(1786793400000L, startAt)
        assertEquals(2026, Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).year)
    }

    @Test
    fun bilibiliGetInfoAreaAndTime() {
        val area = live(
            BilibiliParser.parseGetInfo(
                """{"code":0,"data":{"live_status":1,"title":"bt","parent_area_name":"网游","area_name":"DOTA2"}}""",
            ),
        )
        assertEquals("DOTA2", area.area)

        val time = live(
            BilibiliParser.parseGetInfo(
                """{"code":0,"data":{"live_status":1,"title":"","live_time":"2026-08-15 10:00:00"}}""",
            ),
        )
        assertEquals(epochOf(2026, 8, 15, 10, 0, 0), time.liveStartAt ?: -1L)
    }

    @Test
    fun bilibiliRoomInitAndUidExtraction() {
        assertEquals(
            "6",
            BilibiliParser.parseRoomInitRoomId("""{"code":0,"data":{"room_id":"6","short_id":0}}"""),
        )
        assertEquals(
            "12345",
            BilibiliParser.parseRoomInitRoomId("""{"code":0,"data":{"room_id":12345}}"""),
        )
        assertNull(BilibiliParser.parseRoomInitRoomId("""{"code":-400,"data":null}"""))
        assertNull(BilibiliParser.parseRoomInitRoomId("""{"code":0,"data":{"room_id":"12a"}}"""))
        assertNull(BilibiliParser.parseRoomInitRoomId("broken"))

        assertEquals(
            123456L,
            BilibiliParser.parseGetInfoUid("""{"code":0,"data":{"uid":123456,"live_status":1}}""") ?: -1L,
        )
        assertNull(BilibiliParser.parseGetInfoUid("""{"code":-400}"""))
        assertNull(BilibiliParser.parseGetInfoUid("""{"code":0,"data":{"uid":0}}"""))
    }

    @Test
    fun bilibiliLivePageUnescapesAndSanitizes() {
        val page = live(
            BilibiliParser.parseLivePage(
                """{"live_status":1,"title":"\u76f4\u64ad\u95f4","uname":"up\u0000name"}""",
            ),
        )
        assertEquals("直播间", page.title)
        assertEquals("upname", page.owner)
    }

    @Test
    fun bilibiliLivePageTruncatesLongTitle() {
        val longTitle = "标".repeat(300)
        val page = live(
            BilibiliParser.parseLivePage("""{"live_status":1,"title":"$longTitle","uname":"pn"}"""),
        )
        // 超长标题要么被正则丢弃（空），要么被限长；绝不能原样返回 300 字符
        assertTrue(page.title.length <= PlatformText.MAX_TITLE_LEN)
        assertEquals(PlatformText.MAX_TITLE_LEN, PlatformText.displayText(longTitle, PlatformText.MAX_TITLE_LEN).length)
    }

    // ---------------- 虎牙 ----------------

    @Test
    fun huyaLive() {
        val result = live(HuyaParser.parseHtml("""{"isOn": true, "sNick":"hn", "sRoomName":"ht"}"""))
        assertEquals("hn", result.owner)
        assertEquals("ht", result.title)
    }

    @Test
    fun huyaOffline() {
        offline(HuyaParser.parseHtml("""{"isOn": false}"""))
    }

    @Test
    fun huyaAreaAndFallbacks() {
        val result = live(
            HuyaParser.parseHtml("""{"isOn": true, "sNick":"hn", "gameFullName":"英雄联盟"}"""),
        )
        assertEquals("英雄联盟", result.area)

        val gameName = live(HuyaParser.parseHtml("""{"isOn": true, "gameName":"王者荣耀"}"""))
        assertEquals("王者荣耀", gameName.area)

        val intro = live(HuyaParser.parseHtml("""{"isOn": true, "introduction":"介绍一下"}"""))
        assertEquals("介绍一下", intro.title)
    }

    @Test
    fun huyaNotFoundAndUnparseable() {
        notFound(HuyaParser.parseHtml("<html>页面不存在</html>"))
        notFound(HuyaParser.parseHtml("<html>没有找到该主播</html>"))
        assertNull(HuyaParser.parseHtml("<html>short</html>"))
        assertNull(HuyaParser.parseHtml(null))
    }

    // ---------------- 抖音 ----------------

    @Test
    fun douyinEnterLive() {
        val result = live(
            DouyinParser.parseEnter(
                """{"data":{"data":[{"status":2,"title":"dt","owner":{"nickname":"dn"}}]}}""",
            ),
        )
        assertEquals("dn", result.owner)
        assertEquals("dt", result.title)
    }

    @Test
    fun douyinEnterOffline() {
        offline(DouyinParser.parseEnter("""{"data":{"data":[{"status":4}]}}"""))
    }

    @Test
    fun douyinEnterInvalid() {
        assertNull(DouyinParser.parseEnter("""{"data":{"data":[]}}"""))
        assertNull(DouyinParser.parseEnter("""{"data":{"data":[{"status":1}]}}"""))
        assertNull(DouyinParser.parseEnter("""{"data":null}"""))
        assertNull(DouyinParser.parseEnter("broken"))
        assertNull(DouyinParser.parseEnter(null))
    }

    @Test
    fun douyinEnterArea() {
        val result = live(
            DouyinParser.parseEnter(
                """{"data":{"data":[{"status":2,"title":"dt","game_tag":"游戏"}]}}""",
            ),
        )
        assertEquals("游戏", result.area)

        val fallback = live(
            DouyinParser.parseEnter(
                """{"data":{"data":[{"status":2,"title":"dt","game_name":"户外"}]}}""",
            ),
        )
        assertEquals("户外", fallback.area)
    }

    @Test
    fun douyinPageExtraction() {
        val page = """
            <script>window.__ac_nonce = "0123456789abcdef";</script>
            <script>{"roomId":"7123456789012345678","web_rid":"123456789012345","status":2}</script>
        """.trimIndent()
        assertEquals("0123456789abcdef", DouyinParser.acNonce(page))
        assertEquals("7123456789012345678", DouyinParser.roomId(page))
        assertEquals(2, DouyinParser.pageStatus(page, "123456789012345") ?: -1)
        assertNull(DouyinParser.pageStatus(page, "999999999999999"))

        val ended = """{"web_rid":"123456789012345","status":4}"""
        assertEquals(4, DouyinParser.pageStatus(ended, "123456789012345") ?: -1)
    }

    @Test
    fun douyinNonceRejectsUnsafeValues() {
        assertNull(DouyinParser.acNonce("""window.__ac_nonce = "abc def";"""))
        assertNull(DouyinParser.acNonce("""window.__ac_nonce = "abc\u0000def";"""))
        assertNull(DouyinParser.acNonce("<html>no nonce</html>"))
        assertNull(DouyinParser.acNonce(null))
    }

    @Test
    fun douyinAreaFromPageFallbacks() {
        assertEquals("游戏", DouyinParser.areaFromPage("""{"game_tag":"游戏"}"""))
        assertEquals("户外", DouyinParser.areaFromPage("""{"game_name":"户外"}"""))
        assertEquals("聊天", DouyinParser.areaFromPage("""{"partition_name":"聊天"}"""))
        assertEquals("", DouyinParser.areaFromPage("<html></html>"))
        assertEquals("", DouyinParser.areaFromPage(null))
    }

    // ---------------- 快手 ----------------

    @Test
    fun kuaishouLive() {
        val result = live(KuaishouParser.parseHtml("""{"name":"kn","isLiving":true}"""))
        assertEquals("kn", result.owner)
    }

    @Test
    fun kuaishouOffline() {
        offline(KuaishouParser.parseHtml("""{"isLiving":false}"""))
    }

    @Test
    fun kuaishouNameAfterStatus() {
        val result = live(KuaishouParser.parseHtml("""{"isLiving":true,"name":"ka"}"""))
        assertEquals("ka", result.owner)
    }

    @Test
    fun kuaishouTitleAndArea() {
        val result = live(
            KuaishouParser.parseHtml(
                """{"name":"kn","isLiving":true,"caption":"直播标题","gameName":"王者荣耀"}""",
            ),
        )
        assertEquals("直播标题", result.title)
        assertEquals("王者荣耀", result.area)

        val snake = live(KuaishouParser.parseHtml("""{"isLiving":true,"game_name":"户外"}"""))
        assertEquals("户外", snake.area)
    }

    @Test
    fun kuaishouUnparseableIsNull() {
        assertNull(KuaishouParser.parseHtml("no isLiving here, page too short"))
        assertNull(KuaishouParser.parseHtml(null))
        assertNull(KuaishouParser.parseHtml(""))
    }

    // ---------------- 通用清洗工具 ----------------

    @Test
    fun unescapeUnicode() {
        assertEquals("直播", PlatformText.unescapeUnicode("\\u76f4\\u64ad"))
        assertEquals("", PlatformText.unescapeUnicode(null))
        assertEquals("\\uZZZZ", PlatformText.unescapeUnicode("\\uZZZZ"))
        assertEquals("abc", PlatformText.unescapeUnicode("abc"))
    }

    @Test
    fun sanitizeTextRemovesControlCharsAndLimitsLength() {
        assertEquals("ab", PlatformText.sanitize("a\u0000\u0007b", 10))
        assertEquals("a b", PlatformText.sanitize("a\n\r\tb", 10))
        assertEquals("abc", PlatformText.sanitize("  abc  ", 10))
        assertEquals("ab", PlatformText.sanitize("abcdef", 2))
        assertEquals("", PlatformText.sanitize(null, 10))
        assertEquals("", PlatformText.sanitize("abc", 0))
        assertFalse(PlatformText.sanitize("a\u009fb", 10).any { it.code in 0x80..0x9f })
    }

    @Test
    fun pickArea() {
        assertEquals("最终幻想14", PlatformText.pickArea("网游", "最终幻想14"))
        assertEquals("网游", PlatformText.pickArea("网游", ""))
        assertEquals("", PlatformText.pickArea(null, null))
        assertEquals("网游", PlatformText.pickArea("网游", null))
    }
}
