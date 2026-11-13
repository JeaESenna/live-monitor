package com.livemonitor.app.core.platform

import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.Platform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 平台检测的网络编排层：按桌面版 `PlatformApi.Check*` 的主路径 + 降级路径发起
 * 最少量的公开页面/接口请求，再交给 [PlatformParsers] 里的纯函数解析。
 *
 * 合规约束：
 * - 只访问 [PlatformHosts.requestHosts] 中的公开域名（[HttpFetcher] 每次请求强制校验）；
 * - 不设置代理、不轮换 UA/IP、不识别验证码、不执行 JS、不读取/上传本地 Cookie；
 * - 平台限流（HTTP 403/429）与 HTTP 412 只做分类上报/预热重试，不做绕过；
 * - 每次检测最多少量请求（主路径优先，失败才降级），失败快速返回等下一轮。
 *
 * 可测试性：[HttpTransport] 可注入，JVM 单元测试用假传输离线覆盖每条主/降级路径。
 */
internal class PlatformChecker(
    private val http: HttpTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** B 站首页预热状态：最近一次预热时间（0 = 从未）。 */
    private val biliWarmLock = Any()
    private var biliWarmAt = 0L

    fun check(platform: Platform, id: String, url: String): CheckResult = when (platform) {
        Platform.DOUYU -> checkDouyu(id)
        Platform.BILIBILI -> checkBilibili(id, isBilibiliUidUrl(url))
        Platform.HUYA -> checkHuya(id)
        Platform.DOUYIN -> checkDouyin(id)
        Platform.KUAISHOU -> checkKuaishou(id)
    }

    // ---------------- 斗鱼 ----------------

    private fun checkDouyu(id: String): CheckResult {
        val tracker = FetchTracker()
        var apiErrorCode: Long? = null

        val apiText = fetchTracked(
            "https://open.douyucdn.cn/api/RoomApi/room/$id",
            referer = null,
            tracker = tracker,
        )
        if (apiText != null) {
            val api = DouyuParser.parseRoomApi(apiText)
            if (api != null) {
                if (api.state == LiveState.NOT_FOUND) {
                    // error != 0 也可能是风控/限流：先降级到 betard 再下结论
                    apiErrorCode = DouyuParser.apiErrorCode(apiText)
                } else {
                    return api.copy(src = "douyu-api").sanitized()
                }
            }
        }

        val betardText = fetchTracked(
            "https://www.douyu.com/betard/$id",
            referer = "https://www.douyu.com/",
            tracker = tracker,
        )
        if (betardText != null) {
            val betard = DouyuParser.parseBetard(betardText)
            if (betard != null) return betard.copy(src = "douyu-betard").sanitized()
        }

        if (apiErrorCode == 105L || apiErrorCode == 404L) {
            return CheckResult(
                state = LiveState.NOT_FOUND,
                error = "房间不存在(code=$apiErrorCode)",
            ).sanitized()
        }
        return failureResult(tracker, "斗鱼检测失败")
    }

    // ---------------- B 站 ----------------

    private fun checkBilibili(id: String, isUid: Boolean): CheckResult {
        return try {
            ensureBilibiliWarmed()
            bilibiliInner(id, isUid)
        } catch (_: BiliWarmRequired) {
            // 412：Cookie 被 WAF 拒绝 → 重新预热后最多再试一次（不做任何绕过）
            ensureBilibiliWarmed(force = true)
            try {
                bilibiliInner(id, isUid)
            } catch (_: BiliWarmRequired) {
                CheckResult(
                    state = LiveState.ERROR,
                    error = "平台限流,稍后自动恢复",
                    failure = FailureKind.HTTP_STATUS,
                )
            } catch (_: Exception) {
                CheckResult(
                    state = LiveState.ERROR,
                    error = "B站检测异常",
                    failure = FailureKind.UNKNOWN,
                )
            }
        } catch (_: Exception) {
            CheckResult(
                state = LiveState.ERROR,
                error = "B站检测异常",
                failure = FailureKind.UNKNOWN,
            )
        }
    }

    /**
     * 访问 www.bilibili.com 首页获取 buvid3/b_nut 等公开 Cookie（与正常浏览器访问
     * 完全一致），20 分钟内最多一次。预热失败不影响主流程。
     */
    private fun ensureBilibiliWarmed(force: Boolean = false) {
        synchronized(biliWarmLock) {
            val now = clock()
            if (!force && biliWarmAt > 0L && now - biliWarmAt < BILI_WARM_TTL_MS) return
            try {
                http.get("https://www.bilibili.com/", referer = BILI_REFERER)
            } catch (_: Throwable) {
                // 预热只是降低 412 概率，失败直接进入检测流程
            }
            biliWarmAt = clock()
        }
    }

    /** 412 控制流标记：只在 checkBilibili 内部使用，不携带任何页面信息。 */
    private class BiliWarmRequired : Exception()

    private fun bilibiliInner(id: String, isUid: Boolean): CheckResult {
        val tracker = FetchTracker()

        // 主路径 1：房间号 → 公开直播间网页（与浏览器完全一致，最不容易触发风控）
        if (!isUid) {
            val pageText = fetchTracked("https://live.bilibili.com/$id", BILI_REFERER, tracker)
            throwIfWarmRequired(tracker)
            if (pageText != null) {
                val page = BilibiliParser.parseLivePage(pageText)
                if (page != null) return page.copy(src = "bili-page").sanitized()
            }
        } else {
            // space UID：先 getRoomInfoOld，再个人空间直播页
            val oldText = fetchTracked(
                "https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=$id",
                BILI_REFERER,
                tracker,
            )
            throwIfWarmRequired(tracker)
            if (oldText != null) {
                val old = BilibiliParser.parseGetRoomInfoOld(oldText)
                if (old != null) return old.copy(src = "bili-getRoomInfoOld").sanitized()
            }
            val spaceText = fetchTracked("https://space.bilibili.com/$id/live", BILI_REFERER, tracker)
            throwIfWarmRequired(tracker)
            if (spaceText != null) {
                val space = BilibiliParser.parseLivePage(spaceText)
                if (space != null) return space.copy(src = "bili-space-page").sanitized()
            }
            return failureResult(tracker, "B站用户直播间检测失败")
        }

        // 降级 2：getInfoByRoom（状态 + 主播一次拿全）
        val infoText = fetchTracked(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom?room_id=$id",
            BILI_REFERER,
            tracker,
        )
        throwIfWarmRequired(tracker)
        if (infoText != null) {
            val info = BilibiliParser.parseGetInfoByRoom(infoText)
            if (info != null) return info.copy(src = "bili-getInfoByRoom").sanitized()
        }

        // 降级 3：room_init 拿真实房间号 + get_info
        val initText = fetchTracked(
            "https://api.live.bilibili.com/room/v1/Room/room_init?id=$id",
            BILI_REFERER,
            tracker,
        )
        throwIfWarmRequired(tracker)
        if (initText != null) {
            val realId = BilibiliParser.parseRoomInitRoomId(initText)
            if (realId != null) {
                val getInfoText = fetchTracked(
                    "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$realId",
                    BILI_REFERER,
                    tracker,
                )
                throwIfWarmRequired(tracker)
                if (getInfoText != null) {
                    val getInfo = BilibiliParser.parseGetInfo(getInfoText)
                    if (getInfo != null) {
                        if (getInfo.state == LiveState.NOT_FOUND) {
                            return getInfo.copy(src = "bili-get_info").sanitized()
                        }
                        // 昵称是补充信息：失败不影响状态判定
                        var owner = getInfo.owner
                        val uid = BilibiliParser.parseGetInfoUid(getInfoText)
                        if (uid != null) {
                            val ownerText = fetchTracked(
                                "https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=$uid",
                                BILI_REFERER,
                                tracker,
                            )
                            if (ownerText != null) {
                                val ownerResult = BilibiliParser.parseGetRoomInfoOld(ownerText)
                                if (ownerResult != null && ownerResult.owner.isNotEmpty()) {
                                    owner = ownerResult.owner
                                }
                            }
                        }
                        return getInfo.copy(owner = owner, src = "bili-get_info").sanitized()
                    }
                }
            }
        }

        return failureResult(tracker, "B站检测失败(所有途径均不可用)")
    }

    private fun throwIfWarmRequired(tracker: FetchTracker) {
        if (tracker.lastHttp == 412) throw BiliWarmRequired()
    }

    private fun isBilibiliUidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.host == "space.bilibili.com"
    }

    // ---------------- 虎牙 ----------------

    private fun checkHuya(id: String): CheckResult {
        val tracker = FetchTracker()
        for (pageUrl in listOf("https://www.huya.com/$id", "https://m.huya.com/$id")) {
            val html = fetchTracked(pageUrl, referer = null, tracker = tracker) ?: continue
            val parsed = HuyaParser.parseHtml(html) ?: continue
            return parsed.copy(src = "huya-page").sanitized()
        }
        return failureResult(tracker, "无法解析虎牙页面")
    }

    // ---------------- 抖音 ----------------

    private fun checkDouyin(id: String): CheckResult {
        val tracker = FetchTracker()
        val page = fetchTracked("https://live.douyin.com/$id", referer = null, tracker = tracker)
            ?: return failureResult(tracker, "页面无内容")
        if (page.length < 500) {
            return CheckResult(
                state = LiveState.ERROR,
                error = "页面无内容",
                failure = FailureKind.PARSE,
                src = "douyin-page",
            ).sanitized()
        }
        if (page.contains("直播已结束") && !page.contains("\"status\"")) {
            return CheckResult(state = LiveState.OFFLINE, src = "douyin-page")
        }

        val nonce = DouyinParser.acNonce(page)
        val roomId = DouyinParser.roomId(page)
        if (nonce != null) http.seedCookie("live.douyin.com", "__ac_nonce", nonce)

        if (nonce != null && roomId != null) {
            val enterUrl = buildDouyinEnterUrl(id, roomId)
            if (enterUrl != null) {
                val resp = fetchTracked(enterUrl, referer = "https://live.douyin.com/", tracker = tracker)
                if (resp != null) {
                    val parsed = DouyinParser.parseEnter(resp)
                    if (parsed != null) return parsed.copy(src = "douyin-enter").sanitized()
                }
            }
        }

        // HTML 兜底：web_rid 后附近的 status 字段
        val status = DouyinParser.pageStatus(page, id)
        val area = DouyinParser.areaFromPage(page)
        if (status == 2) {
            return CheckResult(state = LiveState.LIVE, area = area, src = "douyin-page").sanitized()
        }
        if (status == 4) {
            return CheckResult(state = LiveState.OFFLINE, area = area, src = "douyin-page").sanitized()
        }
        return CheckResult(
            state = LiveState.UNKNOWN,
            error = "抖音接口受限,未能确认状态",
            failure = restrictedFailure(tracker),
            src = "douyin-page",
        ).sanitized()
    }

    private fun restrictedFailure(tracker: FetchTracker): FailureKind {
        val httpStatus = tracker.lastHttp
        if (httpStatus == 403 || httpStatus == 429) return FailureKind.HTTP_STATUS
        return if (tracker.lastKind != FailureKind.NONE) tracker.lastKind else FailureKind.UNKNOWN
    }

    private fun buildDouyinEnterUrl(webRid: String, roomId: String): String? = try {
        HttpUrl.Builder()
            .scheme("https")
            .host("live.douyin.com")
            .addPathSegment("webcast")
            .addPathSegment("room")
            .addPathSegment("web")
            .addPathSegment("enter")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("app_name", "douyin_web")
            .addQueryParameter("live_id", "1")
            .addQueryParameter("device_platform", "web")
            .addQueryParameter("language", "zh-CN")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "126.0.0.0")
            .addQueryParameter("web_rid", webRid)
            .addQueryParameter("room_id", roomId)
            .addQueryParameter("enter_from", "web_live")
            .addQueryParameter("is_need_double_stream", "false")
            .addQueryParameter("source", "0")
            .build()
            .toString()
    } catch (_: RuntimeException) {
        null
    }

    // ---------------- 快手 ----------------

    private fun checkKuaishou(id: String): CheckResult {
        val tracker = FetchTracker()
        val html = fetchTracked("https://live.kuaishou.com/u/$id", referer = null, tracker = tracker)
            ?: return failureResult(tracker, "页面无内容")
        val parsed = KuaishouParser.parseHtml(html)
        if (parsed == null) {
            val message = if (html.length < 1000) "页面无内容" else "未能解析直播状态"
            return CheckResult(
                state = LiveState.ERROR,
                error = message,
                failure = FailureKind.PARSE,
                src = "kuaishou-page",
            ).sanitized()
        }
        return parsed.copy(src = "kuaishou-page").sanitized()
    }

    // ---------------- 公共辅助 ----------------

    /** 记录最近一次抓取的失败信息，用于最终的 CheckResult 分类（不保存 URL/内容）。 */
    private class FetchTracker {
        var lastHttp: Int? = null
        var lastKind: FailureKind = FailureKind.NONE
    }

    private fun fetchTracked(url: String, referer: String?, tracker: FetchTracker): String? =
        when (val outcome = http.get(url, referer)) {
            is FetchOutcome.Ok -> outcome.body
            is FetchOutcome.HttpError -> {
                tracker.lastHttp = outcome.status
                tracker.lastKind = FailureKind.HTTP_STATUS
                null
            }

            is FetchOutcome.Failure -> {
                tracker.lastHttp = null
                tracker.lastKind = outcome.kind
                null
            }
        }

    private fun failureResult(tracker: FetchTracker, fallbackMessage: String): CheckResult {
        val httpStatus = tracker.lastHttp
        val kind = when {
            httpStatus != null -> FailureKind.HTTP_STATUS
            tracker.lastKind != FailureKind.NONE -> tracker.lastKind
            else -> FailureKind.PARSE
        }
        val message = when {
            httpStatus == 403 || httpStatus == 429 -> "平台限流,稍后自动恢复"
            httpStatus != null -> "HTTP $httpStatus"
            tracker.lastKind == FailureKind.TIMEOUT -> "请求超时"
            tracker.lastKind == FailureKind.NETWORK -> "网络错误"
            tracker.lastKind == FailureKind.POLICY -> "地址不在允许范围"
            else -> fallbackMessage
        }
        return CheckResult(state = LiveState.ERROR, error = message, failure = kind)
    }

    private companion object {
        const val BILI_REFERER = "https://www.bilibili.com/"
        const val BILI_WARM_TTL_MS = 20L * 60 * 1000
    }
}

/** 生产环境共享的检测器（内部持有共享 OkHttp 抓取器，连接池复用）。 */
internal object PlatformCheckers {
    // 证书固定开关变化时需要换用对应的 OkHttpClient，因此缓存按当前模式重建。
    @Volatile
    private var shared: PlatformChecker? = null

    @Volatile
    private var sharedPinning: Boolean? = null

    fun check(platform: Platform, id: String, url: String): CheckResult {
        val pinning = PlatformHttp.pinningEnabled
        var checker = shared
        if (checker == null || sharedPinning != pinning) {
            synchronized(this) {
                if (shared == null || sharedPinning != pinning) {
                    shared = PlatformChecker(PlatformHttp.fetcher)
                    sharedPinning = pinning
                }
                checker = shared
            }
        }
        return checker!!.check(platform, id, url)
    }
}

/**
 * 对平台返回的所有展示字段做最后一道清洗（解析函数内部已清洗一次，这里是深度防御）。
 * 控制字符、超长、Unicode 转义都在这层兜底处理。
 */
internal fun CheckResult.sanitized(): CheckResult = copy(
    title = PlatformText.displayText(title, PlatformText.MAX_TITLE_LEN),
    owner = PlatformText.displayText(owner, PlatformText.MAX_OWNER_LEN),
    sub = PlatformText.displayText(sub, PlatformText.MAX_SUB_LEN),
    area = PlatformText.displayText(area, PlatformText.MAX_AREA_LEN),
    error = PlatformText.sanitize(error, PlatformText.MAX_ERROR_LEN),
    src = PlatformText.sanitize(src, PlatformText.MAX_SRC_LEN),
)
