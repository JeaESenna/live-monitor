package com.livemonitor.app.core.jump

import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.platform.PlatformHosts
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 一次“进入直播间”所需的全部跳转目标（纯数据，JVM 可测）。
 *
 * 注意：v1.1.0 起**不提供“下载对应 App”功能**——不跳应用市场、不跳官网下载页。
 * 未安装平台 App 时，由 UI 弹窗提示用户自行到应用商店/官网下载安装，或直接用浏览器
 * 看网页版；本模块只负责“打开已安装 App 内的直播间 / App 首页 / 浏览器网页版”。
 *
 * @property platform 平台
 * @property appPackage 平台 App 包名（与 Manifest `<queries>` 中声明的 5 个包名一致）
 * @property appDeepLinks 候选 URI（已按 roomId 替换；**按尝试优先级排序**，首个为 webUrl）
 * @property webUrl 已严格校验的平台直播间网页地址（https + 平台域名精确白名单）
 */
data class JumpTargetSet(
    val platform: Platform,
    val appPackage: String,
    val appDeepLinks: List<String>,
    val webUrl: String,
)

/**
 * “进入直播间”跳转目标的纯逻辑构造（不 import 任何 android.*，可被 JVM 单测直接覆盖）。
 *
 * 安全约束：
 * - `webUrl` 只来自 [PlatformApi.normalizeStoredUrl]（https + 平台精确域名白名单 +
 *   路径与 id 匹配），**绝不接受外部传入 URL**；非法输入返回 null；
 * - deep link 全部由固定模板 + 已校验 roomId（`[A-Za-z0-9_-]{1,64}`）拼接，
 *   不存在任何用户可控的 host/参数拼接；
 * - 不包含任何应用市场 / 下载页 / 第三方 URL 常量，也不查询本机其他应用。
 *
 * 降级顺序（由 [RoomLauncher] 执行）：`webUrl + setPackage(App)`（很多平台 App
 * 注册了自家域名的 App Links，能直接落到直播间）→ 已核实/候选 deep scheme →
 * 打开 App 首页 → 失败。deep link 能力为“尽力而为”：平台 scheme 无官方文档，
 * 部分候选可能无效或需要平台特定参数，失败时由 UI 提示用户浏览器打开网页版。
 */
object JumpTargets {

    private const val MAX_URL_LEN = 512

    /** 规范房间 ID（与 PlatformApi 的 id 规则一致）。 */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f\\u0080-\\u009f]")

    /**
     * 平台 App 包名（已确认，与 AndroidManifest.xml 的 `<queries>` 一一对应；
     * 不要新增查询其他包，避免 QUERY_ALL_PACKAGES 级别的隐私问题）。
     */
    private val APP_PACKAGES: Map<Platform, String> = mapOf(
        Platform.DOUYU to "air.tv.douyu.android",
        Platform.HUYA to "com.duowan.kiwi",
        Platform.BILIBILI to "tv.danmaku.bili",
        Platform.DOUYIN to "com.ss.android.ugc.aweme",
        Platform.KUAISHOU to "com.smile.gifmaker",
    )

    fun appPackage(platform: Platform): String = APP_PACKAGES.getValue(platform)

    /**
     * 校验并构造跳转目标；房间号非法或持久化 URL 不可信时返回 null。
     *
     * @param storedWebUrl 仓储里已校验过的房间 URL（可为空，空时按 id 重建规范 URL）
     */
    fun of(platform: Platform, roomId: String, storedWebUrl: String?): JumpTargetSet? {
        if (!ID_PATTERN.matches(roomId)) return null

        // 唯一 webUrl 来源：PlatformApi 的严格校验（https/443/无 userinfo/平台域名白名单/路径含 id）
        val webUrl = PlatformApi.normalizeStoredUrl(platform, roomId, storedWebUrl) ?: return null
        val parsed = webUrl.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.port != 443) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null

        val links = ArrayList<String>(4)
        // 首选：https 房间页 + setPackage(App)。很多平台 App 注册了自家域名的 App Links，
        // 由系统直接交给 App 打开；App 内再原生跳到直播间（比来源不明的 scheme 更可靠）。
        links.add(webUrl)
        links.addAll(schemeCandidates(platform, roomId, parsed))

        return JumpTargetSet(
            platform = platform,
            appPackage = appPackage(platform),
            appDeepLinks = links.distinct(),
            webUrl = webUrl,
        )
    }

    /**
     * 平台 App 内直播间 deep link 候选（全部由固定模板 + 已校验 roomId 拼接）。
     *
     * 核实情况：
     * - B站 `bilibili://live/{roomId}`：社区/多来源广泛确认（已核实）；
     * - 抖音 `snssdk1128://live?room_id={roomId}`：社区文档确认 scheme 形态（较可信）；
     *   但抖音网页地址里是 web_rid，与接口 room_id 可能不同，打开失败属正常，走降级；
     * - 斗鱼/虎牙/快手：**未检索到可靠官方 scheme**，列出社区常见候选，标注“未核实”，
     *   仅在 webUrl + setPackage 失败后作为尽力尝试；
     * - B站 UID 形态（space.bilibili.com/{uid}/live）：`bilibili://live/{id}` 的 id 是
     *   房间号而不是 UID，套用可能打开别人的直播间 → 该形态只尝试 webUrl。
     */
    private fun schemeCandidates(platform: Platform, roomId: String, webUrl: HttpUrl): List<String> {
        if (platform == Platform.BILIBILI && webUrl.host == "space.bilibili.com") return emptyList()
        return when (platform) {
            // 未核实（社区候选）：douyu://room/{id}
            Platform.DOUYU -> listOf(
                "douyu://room/$roomId",
                "douyu://open?roomid=$roomId",
            )

            // 未核实（社区候选）：huya://room/{id}
            Platform.HUYA -> listOf(
                "huya://room/$roomId",
                "huya://room?roomId=$roomId",
            )

            // 已核实：B站 App 内直播间
            Platform.BILIBILI -> listOf("bilibili://live/$roomId")

            // 较可信（社区文档）：抖音 App 内直播间；room_id 语义与 web_rid 可能不同
            Platform.DOUYIN -> listOf("snssdk1128://live?room_id=$roomId")

            // 未核实（尽力而为）：快手 App 直播页，可能需要直播流 id 而非用户 id
            Platform.KUAISHOU -> listOf("kwai://live/play/$roomId")
        }
    }

    /**
     * 是否允许用系统浏览器打开该 https 地址。
     *
     * 只允许“平台直播间网页域名”（[PlatformHosts.storedUrlHosts] 白名单，和已保存房间
     * URL 的域名一致），拒绝任意 host / 下载页 / 应用市场 / 非 https / 非标准端口 /
     * 带 userinfo 或控制字符的地址。
     */
    internal fun isSafeOpenableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val candidate = url.trim()
        if (candidate.length > MAX_URL_LEN) return false
        if (CONTROL_CHARS.containsMatchIn(candidate)) return false
        val parsed: HttpUrl = candidate.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        if (parsed.port != 443) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return PlatformHosts.isAllowedStoredHost(parsed.host)
    }
}
