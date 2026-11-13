package com.livemonitor.app.core.platform

import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.LiveState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 各平台公开页面/接口的**纯解析函数**（字符串进 → CheckResult 出）。
 *
 * 设计约束（安全评审重点）：
 * - 不发起任何网络请求，可被 JVM 单元测试完全离线覆盖；
 * - 所有 JSON/HTML 输入都视为不可信：类型错误、字段缺失、截断、`null` 一律
 *   安全降级（返回 null 或空字段），绝不抛异常；
 * - 正则都带长度上界（`{1,60}` / `{0,600}`），避免灾难性回溯；
 * - 解析出的展示字段统一走 [PlatformText.displayText] 清洗（去控制字符 + 限长）；
 * - 解析约定：可以识别但状态未知/字段异常时返回 null，由调用方决定降级路径。
 */

// ---------------- JSON 防御性取值（不依赖 org.json 的隐式类型转换） ----------------

internal fun JSONObject.strOr(key: String, default: String = ""): String {
    if (!has(key) || isNull(key)) return default
    return when (val value = opt(key)) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> default
    }
}

internal fun JSONObject.longOr(key: String, default: Long = 0L): Long {
    if (!has(key) || isNull(key)) return default
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong() ?: default
        else -> default
    }
}

private fun JSONObject.objOrNull(key: String): JSONObject? = try {
    optJSONObject(key)
} catch (_: RuntimeException) {
    null
}

private fun JSONObject.arrayOrNull(key: String): JSONArray? = try {
    optJSONArray(key)
} catch (_: RuntimeException) {
    null
}

private fun jsonObjectOrNull(text: String?): JSONObject? {
    if (text.isNullOrBlank() || text.length > MAX_JSON_LEN) return null
    return try {
        JSONObject(text)
    } catch (_: RuntimeException) {
        null
    }
}

private const val MAX_JSON_LEN = 4 * 1024 * 1024

private fun regexGroup(input: String?, regex: Regex): String {
    if (input.isNullOrEmpty()) return ""
    return try {
        regex.find(input)?.groupValues?.getOrNull(1).orEmpty()
    } catch (_: RuntimeException) {
        ""
    }
}

private fun regexInt(input: String?, regex: Regex): Int? {
    if (input.isNullOrEmpty()) return null
    return try {
        regex.find(input)?.groupValues?.getOrNull(1)?.toIntOrNull()
    } catch (_: RuntimeException) {
        null
    }
}

private fun baseResult(
    live: Boolean,
    title: String,
    owner: String,
    roundPlay: Boolean = false,
): CheckResult {
    val result = CheckResult(
        state = if (live) LiveState.LIVE else LiveState.OFFLINE,
        title = PlatformText.displayText(title, PlatformText.MAX_TITLE_LEN),
        owner = PlatformText.displayText(owner, PlatformText.MAX_OWNER_LEN),
    )
    return if (live && roundPlay) result.copy(sub = "轮播中") else result
}

// =====================================================================================
// 斗鱼
// =====================================================================================

internal object DouyuParser {

    /**
     * 解析 open.douyucdn.cn/api/RoomApi/room/{id}：
     * `{"error":0,"data":{"room_status":"1","room_name":"","owner_name":"","cate_name":"","start_time":""}}`
     *
     * `error != 0` 视为“房间不存在/被风控”（桌面版同样先降级到 betard 再下结论）。
     * JSON 结构完全无法识别时返回 null。
     */
    fun parseRoomApi(text: String?): CheckResult? {
        val root = jsonObjectOrNull(text) ?: return null
        val error = root.longOr("error")
        if (error != 0L) {
            return CheckResult(
                state = LiveState.NOT_FOUND,
                error = "房间不存在(code=$error)",
            )
        }
        val data = root.objOrNull("data") ?: return null
        val live = data.strOr("room_status") == "1"
        val result = baseResult(live, data.strOr("room_name"), data.strOr("owner_name"))
        val area = PlatformText.pickArea("", data.strOr("cate_name"))
        val startAt = if (live) PlatformTime.parseDate(data.strOr("start_time")) else null
        return result.copy(area = area, liveStartAt = startAt)
    }

    /** API 返回的错误码（用于区分“房间不存在”与“平台限流”）。 */
    fun apiErrorCode(text: String?): Long? {
        val root = jsonObjectOrNull(text) ?: return null
        val error = root.longOr("error")
        return if (error != 0L) error else null
    }

    /**
     * 解析降级路径 www.douyu.com/betard/{id}：
     * `{"error":0,"room":{"show_status":"1","room_name":"","nickname":"","cate_name":"","start_time":""}}`
     */
    fun parseBetard(text: String?): CheckResult? {
        val root = jsonObjectOrNull(text) ?: return null
        val room = root.objOrNull("room") ?: return null
        val live = room.strOr("show_status") == "1"
        val result = baseResult(live, room.strOr("room_name"), room.strOr("nickname"))
        val area = PlatformText.pickArea("", room.strOr("cate_name"))
        val startAt = if (live) PlatformTime.parseDate(room.strOr("start_time")) else null
        return result.copy(area = area, liveStartAt = startAt)
    }
}

// =====================================================================================
// B 站
// =====================================================================================

internal object BilibiliParser {

    private val LIVE_STATUS = Regex("\"live_status\"\\s*:\\s*(\\d)")
    private val UNAME = Regex("\"uname\"\\s*:\\s*\"([^\"]{1,60})\"")
    private val TITLE = Regex("\"title\"\\s*:\\s*\"([^\"]{1,120})\"")
    private val PARENT_AREA = Regex("\"parent_area_name\"\\s*:\\s*\"([^\"]{1,40})\"")
    private val AREA = Regex("\"area_name\"\\s*:\\s*\"([^\"]{1,60})\"")
    private val LIVE_START_UNIX = Regex("\"live_start_time\"\\s*:\\s*(\\d{9,12})")
    private val LIVE_TIME_STR = Regex("\"live_time\"\\s*:\\s*\"([^\"]{4,30})\"")
    private val LIVE_TIME_UNIX = Regex("\"live_time\"\\s*:\\s*(\\d{9,12})")
    private val LIVE_TIME_CAMEL = Regex("\"liveTime\"\\s*:\\s*\"([^\"]{4,30})\"")

    /**
     * 解析公开直播间网页（与正常浏览器访问完全一致的页面，风险最低）。
     * 页面内嵌 `"live_status":1/2` 时才算解析成功；否则返回 null 交给接口降级。
     */
    fun parseLivePage(html: String?): CheckResult? {
        if (html.isNullOrEmpty() || html.length > MAX_HTML_LEN) return null
        val statusMatch = try {
            LIVE_STATUS.find(html)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val status = statusMatch.groupValues[1].toIntOrNull() ?: return null
        val live = status == 1 || status == 2

        // 昵称/标题通常在状态字段之后的内嵌 JSON 里；先查尾部，找不到再全页查。
        val tail = try {
            html.substring(statusMatch.range.first)
        } catch (_: RuntimeException) {
            html
        }
        var uname = regexGroup(tail, UNAME)
        if (uname.isEmpty()) uname = regexGroup(html, UNAME)
        var title = regexGroup(tail, TITLE)
        if (title.isEmpty()) title = regexGroup(html, TITLE)

        val result = baseResult(live, title, uname, roundPlay = status == 2)
        val area = PlatformText.pickArea(
            regexGroup(html, PARENT_AREA),
            regexGroup(html, AREA),
        )
        var startAt: Long? = null
        if (live) {
            startAt = firstStartTime(tail, html)
        }
        return result.copy(area = area, liveStartAt = startAt)
    }

    private fun firstStartTime(tail: String, html: String): Long? {
        // roomInfoRes (getInfoByRoom) 风格："live_start_time":1786793400
        var match = regexGroup(tail, LIVE_START_UNIX)
        if (match.isEmpty()) match = regexGroup(html, LIVE_START_UNIX)
        var parsed = PlatformTime.parseUnixSeconds(match.toLongOrNull())

        // 旧页面风格："live_time":"2026-08-15 14:00:00"
        if (parsed == null) {
            var text = regexGroup(tail, LIVE_TIME_STR)
            if (text.isEmpty()) text = regexGroup(html, LIVE_TIME_STR)
            parsed = PlatformTime.parseDate(text)
        }

        // roomInitRes 风格："live_time":1793446476（秒级 Unix）
        if (parsed == null) {
            var text = regexGroup(tail, LIVE_TIME_UNIX)
            if (text.isEmpty()) text = regexGroup(html, LIVE_TIME_UNIX)
            parsed = PlatformTime.parseUnixSeconds(text.toLongOrNull())
        }

        // camelCase："liveTime":"2026-08-15 14:00:00"
        if (parsed == null) {
            var text = regexGroup(tail, LIVE_TIME_CAMEL)
            if (text.isEmpty()) text = regexGroup(html, LIVE_TIME_CAMEL)
            parsed = PlatformTime.parseDate(text)
        }
        return parsed
    }

    /**
     * 解析 xlive/web-room/v1/index/getInfoByRoom（状态 + 主播名一次拿全）。
     * code != 0 或结构异常返回 null（桌面版同样继续走 room_init 降级）。
     */
    fun parseGetInfoByRoom(text: String?): CheckResult? {
        val root = jsonObjectOrNull(text) ?: return null
        if (root.longOr("code") != 0L) return null
        val data = root.objOrNull("data") ?: return null
        val roomInfo = data.objOrNull("room_info") ?: return null
        val liveStatus = roomInfo.longOr("live_status")
        val live = liveStatus == 1L || liveStatus == 2L
        val anchorInfo = data.objOrNull("anchor_info")
        val baseInfo = anchorInfo?.objOrNull("base_info")
        val result = baseResult(
            live = live,
            title = roomInfo.strOr("title"),
            owner = baseInfo?.strOr("uname").orEmpty(),
            roundPlay = liveStatus == 2L,
        )
        val area = PlatformText.pickArea(
            roomInfo.strOr("parent_area_name"),
            roomInfo.strOr("area_name"),
        )
        val startAt = if (live) PlatformTime.parseUnixSeconds(roomInfo.longOr("live_start_time")) else null
        return result.copy(area = area, liveStartAt = startAt)
    }

    /** 解析 room/v1/Room/get_info；code != 0 返回 NOT_FOUND。 */
    fun parseGetInfo(text: String?): CheckResult? {
        val root = jsonObjectOrNull(text) ?: return null
        if (root.longOr("code") != 0L) {
            return CheckResult(state = LiveState.NOT_FOUND, error = "房间不存在")
        }
        val data = root.objOrNull("data") ?: return null
        val liveStatus = data.longOr("live_status")
        val live = liveStatus == 1L || liveStatus == 2L
        val result = baseResult(
            live = live,
            title = data.strOr("title"),
            owner = "",
            roundPlay = liveStatus == 2L,
        )
        val area = PlatformText.pickArea(data.strOr("parent_area_name"), data.strOr("area_name"))
        val startAt = if (live) PlatformTime.parseDate(data.strOr("live_time")) else null
        return result.copy(area = area, liveStartAt = startAt)
    }

    /** 解析 room/v1/Room/getRoomInfoOld（space UID 链接专用）；code != 0 返回 NOT_FOUND。 */
    fun parseGetRoomInfoOld(text: String?): CheckResult? {
        val root = jsonObjectOrNull(text) ?: return null
        if (root.longOr("code") != 0L) {
            return CheckResult(state = LiveState.NOT_FOUND, error = "房间不存在")
        }
        val data = root.objOrNull("data") ?: return null
        val liveStatus = data.longOr("liveStatus")
        val roundStatus = data.longOr("roundStatus")
        var live = liveStatus == 1L
        var roundPlay = false
        if (!live && roundStatus == 1L) {
            live = true
            roundPlay = true
        }
        val result = baseResult(
            live = live,
            title = data.strOr("title"),
            owner = data.strOr("uname"),
            roundPlay = roundPlay,
        )
        val startAt = if (live) PlatformTime.parseDate(data.strOr("liveTime")) else null
        return result.copy(liveStartAt = startAt)
    }

    /** room_init 返回的真实房间号（用于把短号/靓号解析成实际房间）。 */
    fun parseRoomInitRoomId(text: String?): String? {
        val root = jsonObjectOrNull(text) ?: return null
        if (root.longOr("code") != 0L) return null
        val data = root.objOrNull("data") ?: return null
        val roomId = data.strOr("room_id").trim()
        if (roomId.isEmpty() || roomId.length > 20 || !roomId.all { it.isDigit() }) return null
        return roomId
    }

    /** get_info 里的主播 uid（用于补全主播昵称，失败不影响主流程）。 */
    fun parseGetInfoUid(text: String?): Long? {
        val root = jsonObjectOrNull(text) ?: return null
        if (root.longOr("code") != 0L) return null
        val data = root.objOrNull("data") ?: return null
        val uid = data.longOr("uid")
        return if (uid > 0L) uid else null
    }

    private const val MAX_HTML_LEN = 4 * 1024 * 1024
}

// =====================================================================================
// 虎牙
// =====================================================================================

internal object HuyaParser {

    private val IS_ON = Regex("\"isOn\"\\s*:\\s*(true|false)")
    private val S_NICK = Regex("\"sNick\"\\s*:\\s*\"([^\"]{1,60})\"")
    private val S_ROOM_NAME = Regex("\"sRoomName\"\\s*:\\s*\"([^\"]{1,120})\"")
    private val INTRODUCTION = Regex("\"introduction\"\\s*:\\s*\"([^\"]{1,120})\"")
    private val GAME_FULL_NAME = Regex("\"gameFullName\"\\s*:\\s*\"([^\"]{1,40})\"")
    private val GAME_NAME = Regex("\"gameName\"\\s*:\\s*\"([^\"]{1,40})\"")

    /**
     * 解析 www.huya.com/{id} 或 m.huya.com/{id} 页面。
     * 页面里明确写着“页面不存在/没有找到该主播”时返回 NOT_FOUND；
     * 无法识别（反爬页、结构变化）返回 null，交给调用方尝试移动版/报错。
     */
    fun parseHtml(html: String?): CheckResult? {
        if (html.isNullOrEmpty()) return null
        if (html.contains("页面不存在") || html.contains("没有找到该主播")) {
            return CheckResult(state = LiveState.NOT_FOUND, error = "房间不存在")
        }
        val liveMatch = try {
            IS_ON.find(html)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val live = liveMatch.groupValues[1] == "true"
        val nick = regexGroup(html, S_NICK)
        var title = regexGroup(html, S_ROOM_NAME)
        if (title.isEmpty()) title = regexGroup(html, INTRODUCTION)
        val result = baseResult(live, title, nick)
        var area = regexGroup(html, GAME_FULL_NAME)
        if (area.isEmpty()) area = regexGroup(html, GAME_NAME)
        return result.copy(area = PlatformText.displayText(area, PlatformText.MAX_AREA_LEN))
    }
}

// =====================================================================================
// 抖音
// =====================================================================================

internal object DouyinParser {

    private val AC_NONCE = Regex("window\\.__ac_nonce\\s*=\\s*\"([^\"]{1,128})\"")
    private val ROOM_ID = Regex("\"roomId\"\\s*:\\s*\"(\\d{1,32})\"")
    private val GAME_TAG = Regex("\"game_tag\"\\s*:\\s*\"([^\"]{1,40})\"")
    private val GAME_NAME = Regex("\"game_name\"\\s*:\\s*\"([^\"]{1,40})\"")
    private val PARTITION_NAME = Regex("\"partition_name\"\\s*:\\s*\"([^\"]{1,40})\"")

    /** 页面里的 `window.__ac_nonce = "..."`（风控 WAF 下发的短期 Cookie）。 */
    fun acNonce(page: String?): String? {
        val value = regexGroup(page, AC_NONCE)
        if (value.isEmpty() || !value.all { it.isLetterOrDigit() }) return null
        return value
    }

    /** 页面内嵌的 `"roomId":"<数字>"`。 */
    fun roomId(page: String?): String? = regexGroup(page, ROOM_ID).takeIf { it.isNotEmpty() }

    /** 纯 HTML 兜底：在 web_rid 后 600 字符内找 `"status":2/4`。 */
    fun pageStatus(page: String?, id: String): Int? {
        if (page.isNullOrEmpty() || id.isEmpty()) return null
        val pattern = try {
            // web_rid 后 600 字符内找 status：用长度上界避免灾难性回溯
            Regex(
                "\"web_rid\"\\s*:\\s*\"?" + Regex.escape(id) +
                    "\"?\\s*[,\\}]\\s*[\\s\\S]{0,600}?\"status\"\\s*:\\s*(\\d)",
            )
        } catch (_: RuntimeException) {
            return null
        }
        return regexInt(page, pattern)
    }

    /** 解析 webcast/room/web/enter 接口；status==2 直播中，==4 未开播，其余返回 null。 */
    fun parseEnter(text: String?): CheckResult? {
        val root = jsonObjectOrNull(text) ?: return null
        val data = root.objOrNull("data") ?: return null
        val rooms = data.arrayOrNull("data") ?: return null
        if (rooms.length() == 0) return null
        val room = try {
            rooms.optJSONObject(0)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val status = room.longOr("status")
        val title = room.strOr("title")
        val owner = room.objOrNull("owner")?.strOr("nickname").orEmpty()
        var area = room.strOr("game_tag")
        if (area.isEmpty()) area = room.strOr("game_name")
        return when (status) {
            2L -> baseResult(live = true, title = title, owner = owner)
                .copy(area = PlatformText.displayText(area, PlatformText.MAX_AREA_LEN))

            4L -> baseResult(live = false, title = title, owner = owner)
                .copy(area = PlatformText.displayText(area, PlatformText.MAX_AREA_LEN))

            else -> null
        }
    }

    /** HTML 兜底时的分区字段。 */
    fun areaFromPage(page: String?): String {
        var area = regexGroup(page, GAME_TAG)
        if (area.isEmpty()) area = regexGroup(page, GAME_NAME)
        if (area.isEmpty()) area = regexGroup(page, PARTITION_NAME)
        return PlatformText.displayText(area, PlatformText.MAX_AREA_LEN)
    }
}

// =====================================================================================
// 快手
// =====================================================================================

internal object KuaishouParser {

    private val IS_LIVING = Regex("\"isLiving\"\\s*:\\s*(true|false)")
    private val NAME_BEFORE = Regex("\"name\"\\s*:\\s*\"([^\"]{1,40})\"[^}]{0,800}?\"isLiving\"")
    private val NAME_AFTER = Regex("\"isLiving\"[^}]{0,800}?\"name\"\\s*:\\s*\"([^\"]{1,40})\"")
    private val CAPTION = Regex("\"caption\"\\s*:\\s*\"([^\"]{1,120})\"")
    private val GAME_NAME_CAMEL = Regex("\"gameName\"\\s*:\\s*\"([^\"]{1,40})\"")
    private val GAME_NAME_SNAKE = Regex("\"game_name\"\\s*:\\s*\"([^\"]{1,40})\"")

    /** 解析 live.kuaishou.com/u/{id} 页面；找不到 isLiving 字段返回 null。 */
    fun parseHtml(html: String?): CheckResult? {
        if (html.isNullOrEmpty()) return null
        val liveMatch = try {
            IS_LIVING.find(html)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val live = liveMatch.groupValues[1] == "true"

        var name = regexGroup(html, NAME_BEFORE)
        if (name.isEmpty()) name = regexGroup(html, NAME_AFTER)
        val title = regexGroup(html, CAPTION)
        val result = baseResult(live, title, name)
        var area = regexGroup(html, GAME_NAME_CAMEL)
        if (area.isEmpty()) area = regexGroup(html, GAME_NAME_SNAKE)
        return result.copy(area = PlatformText.displayText(area, PlatformText.MAX_AREA_LEN))
    }
}
