package com.livemonitor.app.core.platform

import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.Platform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 平台检测入口（UI / 服务 / 仓储都依赖的公开契约）。
 *
 * 契约（禁止随意改动签名）：
 * - [parseInput]：把用户粘贴的链接或房间号解析为平台 + 规范 id + 规范 URL；
 * - [normalizeStoredUrl]：校验并规范化持久化里的 URL（防手改配置注入），非法返回 null；
 * - [check]：执行一次网络检测，内部只访问各平台公开页面/公开接口，对响应做防御性
 *   解析；任何异常都转成 [CheckResult]（**check 本身绝不抛异常**）；
 * - [sanitizeLiveStartAt]：过滤平台返回的不合理开播时间。
 *
 * 安全设计：
 * - 输入解析只产出“由本类拼出来的规范 https URL”，绝不回显/使用用户提供的 host；
 * - 持久化 URL 必须 https + 标准端口 + 无账号密码 + 精确平台域名白名单，否则 null；
 * - 网络请求的每次跳转都由 [HttpFetcher] 重新校验域名白名单（防 SSRF / 重定向劫持）。
 */
object PlatformApi {

    private const val MAX_URL_LEN = 512
    private const val MAX_INPUT_LEN = 2048
    private const val MAX_ID_DIGITS = 20

    /** C0/C1 控制字符：粘贴内容里出现即拒绝（防协议注入/日志注入）。 */
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f\\u0080-\\u009f]")

    /** 规范房间 ID：只允许字母、数字、下划线、短横线。 */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    private val SCHEME_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.-]{0,15}):")

    private val DIGITS_PATTERN = Regex("\\d{1,20}")

    /** 快手用户 ID（链接/纯 ID 形态都只允许这个字符集）。 */
    private val KUAISHOU_ID_PATTERN = Regex("[A-Za-z0-9_-]{2,40}")

    data class ParsedInput(
        val platform: Platform,
        val id: String,
        val url: String,
    )

    sealed interface ParseOutcome {
        data class Ok(val input: ParsedInput) : ParseOutcome
        data class Failure(val message: String) : ParseOutcome
    }

    // ---------------- 输入解析 ----------------

    /**
     * 解析用户输入：
     * - 支持斗鱼/虎牙/B站/抖音/快手的直播间链接（http/https，可省略协议）；
     * - 支持纯房间号（必须同时指定 [platformHint]，自动识别无法确定平台）；
     * - B站 `space.bilibili.com/{uid}` 识别为 UID，而非房间号；
     * - 快手仅接受 `/u/{id}` 或 `/profile/{id}`（以及纯 ID）；
     * - 抖音拒绝 `v.douyin.com` 短链接（解析出的地址必须来自直播间完整地址）；
     * - 拒绝搜索页、file:/javascript: 等非 http(s) 协议、控制字符、超长输入。
     *
     * [platformHint] 非空时优先按指定平台解析；为空时只从 host 含平台域名自动识别。
     */
    fun parseInput(input: String, platformHint: Platform? = null): ParseOutcome {
        val raw = input.trim()
        if (raw.isEmpty()) return ParseOutcome.Failure("请输入直播间链接或房间号")
        if (raw.length > MAX_INPUT_LEN) {
            return ParseOutcome.Failure("输入内容过长，请粘贴直播间链接或房间号")
        }
        if (CONTROL_CHARS.containsMatchIn(raw)) {
            return ParseOutcome.Failure("输入包含不允许的字符")
        }

        val schemeMatch = SCHEME_PATTERN.find(raw)
        if (schemeMatch != null) {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") {
                return ParseOutcome.Failure("仅支持 http/https 直播间链接")
            }
            if (!raw.startsWith("$scheme://", ignoreCase = true)) {
                return ParseOutcome.Failure("链接格式不正确，请粘贴完整的直播间地址")
            }
            return parseLink(raw, platformHint)
        }

        // 省略协议的链接（如 www.douyu.com/9999）按链接处理；否则按房间号处理。
        return if (raw.contains('/') || raw.contains('.')) {
            parseLink("https://$raw", platformHint)
        } else {
            parseBareId(raw, platformHint)
        }
    }

    private fun parseLink(urlText: String, platformHint: Platform?): ParseOutcome {
        val url: HttpUrl = urlText.toHttpUrlOrNull()
            ?: return ParseOutcome.Failure("链接格式不正确，请粘贴完整的直播间地址")
        if (url.scheme != "https" && url.scheme != "http") {
            return ParseOutcome.Failure("仅支持 http/https 直播间链接")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return ParseOutcome.Failure("链接不能包含账号密码等认证信息")
        }
        val host = url.host
        // 与桌面版一致：链接自带平台域名时以链接为准（避免用户选错平台后监测到错误房间），
        // 平台域名无法识别时才使用 platformHint（纯房间号/非平台链接的场景）。
        val detected = detectPlatform(host) ?: platformHint ?: run {
            return ParseOutcome.Failure(
                "无法识别平台。请先选择平台,或粘贴斗鱼/虎牙/B站/抖音/快手的直播间链接",
            )
        }

        if (host.contains("v.douyin.com")) {
            return ParseOutcome.Failure("暂不支持抖音短链接,请打开直播间后复制浏览器完整地址")
        }
        if (url.pathSegments.any { it.equals("search", ignoreCase = true) }) {
            return ParseOutcome.Failure("请粘贴具体直播间的链接或房间号")
        }
        if (detected == Platform.DOUYIN && host.contains("douyin")) {
            if (host != "live.douyin.com" && !host.endsWith(".live.douyin.com")) {
                return ParseOutcome.Failure("请打开直播间后复制浏览器完整地址（live.douyin.com/房间号）")
            }
        }

        if (detected == Platform.KUAISHOU) {
            val id = kuaishouIdFromSegments(url.pathSegments)
                ?: return ParseOutcome.Failure("未从链接中提取到快手用户ID")
            val canonical = buildUrl(Platform.KUAISHOU, id)
                ?: return ParseOutcome.Failure("未从链接中提取到有效的快手用户ID")
            return ParseOutcome.Ok(ParsedInput(Platform.KUAISHOU, id, canonical))
        }

        val numericSource = buildString {
            append(url.encodedPath)
            val query = url.query
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
        val id = DIGITS_PATTERN.find(numericSource)?.value
            ?: return ParseOutcome.Failure("未从链接中提取到数字ID")

        // B站个人空间链接：取 UID，规范 URL 指向 space 的直播页
        if (detected == Platform.BILIBILI && host == "space.bilibili.com") {
            val canonical = buildBilibiliUidUrl(id)
                ?: return ParseOutcome.Failure("未从链接中提取到有效的B站UID")
            return ParseOutcome.Ok(ParsedInput(Platform.BILIBILI, id, canonical))
        }

        val canonical = buildUrl(detected, id)
            ?: return ParseOutcome.Failure("未从链接中提取到有效的房间号")
        return ParseOutcome.Ok(ParsedInput(detected, id, canonical))
    }

    private fun parseBareId(raw: String, platformHint: Platform?): ParseOutcome {
        if (platformHint == null) {
            return ParseOutcome.Failure(
                "无法识别平台。请先选择平台,或粘贴斗鱼/虎牙/B站/抖音/快手的直播间链接",
            )
        }
        if (platformHint == Platform.KUAISHOU) {
            if (!KUAISHOU_ID_PATTERN.matches(raw)) {
                return ParseOutcome.Failure("未从输入中提取到有效的快手用户ID")
            }
            val canonical = buildUrl(platformHint, raw)
                ?: return ParseOutcome.Failure("未从输入中提取到有效的快手用户ID")
            return ParseOutcome.Ok(ParsedInput(platformHint, raw, canonical))
        }
        if (!raw.all { it.isDigit() } || raw.length > MAX_ID_DIGITS) {
            return ParseOutcome.Failure("未从输入中提取到数字房间号")
        }
        val canonical = buildUrl(platformHint, raw)
            ?: return ParseOutcome.Failure("未从输入中提取到有效的房间号")
        return ParseOutcome.Ok(ParsedInput(platformHint, raw, canonical))
    }

    /** 平台自动识别：只依据 host 里是否包含对应平台域名，不读取路径/参数上的任何提示。 */
    private fun detectPlatform(host: String): Platform? = when {
        host.contains("douyu") -> Platform.DOUYU
        host.contains("huya") -> Platform.HUYA
        host.contains("bilibili") -> Platform.BILIBILI
        host.contains("douyin") -> Platform.DOUYIN
        host.contains("kuaishou") -> Platform.KUAISHOU
        else -> null
    }

    private fun kuaishouIdFromSegments(segments: List<String>): String? {
        if (segments.size >= 2) {
            val head = segments[0].lowercase()
            if ((head == "u" || head == "profile") && KUAISHOU_ID_PATTERN.matches(segments[1])) {
                return segments[1]
            }
        }
        // 少量变体链接：取路径中第一个合法的用户 ID 段
        return segments.firstOrNull { KUAISHOU_ID_PATTERN.matches(it) }
    }

    // ---------------- 规范 URL ----------------

    /** 按平台 + 规范 ID 拼出规范 https URL；ID 非法返回 null。 */
    fun buildUrl(platform: Platform, id: String): String? {
        if (!ID_PATTERN.matches(id)) return null
        return when (platform) {
            Platform.DOUYU -> "https://www.douyu.com/$id"
            Platform.HUYA -> "https://www.huya.com/$id"
            Platform.BILIBILI -> "https://live.bilibili.com/$id"
            Platform.DOUYIN -> "https://live.douyin.com/$id"
            Platform.KUAISHOU -> "https://live.kuaishou.com/u/$id"
        }
    }

    private fun buildBilibiliUidUrl(uid: String): String? {
        if (!ID_PATTERN.matches(uid)) return null
        return "https://space.bilibili.com/$uid/live"
    }

    /**
     * 校验/规范化持久化的直播间 URL。**安全关键**：配置被手改成任意地址时，
     * 这里必须拒绝，从根上避免 SSRF / 协议注入 / 访问任意主机。
     *
     * 只接受：
     * - `https`（不接受 http/file/javascript/data 等）；
     * - 端口 443 或省略；URL 内不能携带 username/password；
     * - 无控制字符，长度 ≤ [MAX_URL_LEN]；
     * - host 精确等于该平台允许的房间页域名（见 [PlatformHosts.storedUrlHosts]）；
     * - 路径与 id 匹配（B站 space 链接按 uid 处理）。
     *
     * 合法时返回**由本类重新拼出的规范 URL**（丢弃原 URL 的 query/fragment），
     * 这样持久化层永远不会存下一个不由平台域名+已校验 id 组成的地址。
     */
    fun normalizeStoredUrl(platform: Platform, id: String, storedUrl: String?): String? {
        if (!ID_PATTERN.matches(id)) return null
        val fallback = buildUrl(platform, id) ?: return null
        val candidate = storedUrl?.trim().orEmpty()
        if (candidate.isEmpty()) return fallback
        if (candidate.length > MAX_URL_LEN) return null
        if (CONTROL_CHARS.containsMatchIn(candidate)) return null

        val url: HttpUrl = candidate.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val host = url.host.lowercase()
        val allowedHosts = PlatformHosts.storedUrlHosts[platform] ?: return null
        if (host !in allowedHosts) return null

        val segments = url.pathSegments.filter { it.isNotEmpty() }
        when (platform) {
            Platform.BILIBILI -> {
                if (host == "space.bilibili.com") {
                    // space UID 链接：/{uid} 或 /{uid}/live
                    if (segments.size !in 1..2) return null
                    if (segments[0] != id) return null
                    if (segments.size == 2 && segments[1] != "live") return null
                    return buildBilibiliUidUrl(id)
                }
                if (segments.size != 1 || segments[0] != id) return null
                return fallback
            }

            Platform.KUAISHOU -> {
                // 规范形态：/u/{id}（兼容历史 /profile/{id}，统一改写成 /u/{id}）
                if (segments.size != 2) return null
                if (segments[0] != "u" && segments[0] != "profile") return null
                if (segments[1] != id) return null
                return fallback
            }

            else -> {
                if (segments.size != 1 || segments[0] != id) return null
                return fallback
            }
        }
    }

    // ---------------- 检测 ----------------

    /**
     * 执行一次检测（阻塞式；由服务在 `Dispatchers.IO` 上调用）。
     *
     * [url] 只用于判断 B站 space UID 形态，**不会**直接拿它发请求：所有请求地址都由
     * 平台 + 已校验 id 重新构造，并从 [PlatformHosts] 白名单域名发出。
     *
     * 任何异常都转成 `CheckResult(ERROR, failure=分类)`，本方法不抛异常。
     */
    fun check(platform: Platform, id: String, url: String): CheckResult {
        return try {
            if (!ID_PATTERN.matches(id)) {
                return CheckResult(
                    state = LiveState.ERROR,
                    error = "房间号格式不正确",
                    failure = FailureKind.PARSE,
                    src = "invalid-id",
                )
            }
            PlatformCheckers.check(platform, id, url).sanitized()
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            CheckResult(
                state = LiveState.ERROR,
                error = "检测异常",
                failure = FailureKind.UNKNOWN,
            )
        }
    }

    // ---------------- 时间过滤 ----------------

    /**
     * 过滤明显不可信的开播时间：只接受 [now-30 天, now+10 分钟]。
     * 平台偶发的缓存旧值、时区错乱、正则误匹配都会被丢弃，由调用方回退到本地检测时间。
     */
    fun sanitizeLiveStartAt(epochMillis: Long?, nowMillis: Long): Long? {
        if (epochMillis == null || epochMillis <= 0L) return null
        if (epochMillis > nowMillis + 10 * 60 * 1000L) return null
        if (epochMillis < nowMillis - 30L * 24 * 60 * 60 * 1000L) return null
        return epochMillis
    }
}
