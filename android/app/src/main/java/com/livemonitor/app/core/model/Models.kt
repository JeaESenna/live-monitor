package com.livemonitor.app.core.model

/**
 * 支持的直播平台。只保留“开播状态提醒”所需的最小数据集。
 */
enum class Platform(val key: String, val displayName: String) {
    DOUYU("douyu", "斗鱼"),
    HUYA("huya", "虎牙"),
    BILIBILI("bilibili", "B站"),
    DOUYIN("douyin", "抖音"),
    KUAISHOU("kuaishou", "快手");

    companion object {
        fun fromKey(key: String?): Platform? = entries.firstOrNull { it.key == key }

        fun allKeys(): Set<String> = entries.mapTo(LinkedHashSet()) { it.key }
    }
}

/** 检测结果的原始状态，与平台无关。 */
enum class LiveState { UNKNOWN, OFFLINE, LIVE, NOT_FOUND, ERROR }

/** 网络层/解析层的失败分类，用于服务端背压与界面提示（不包含敏感内容）。 */
enum class FailureKind { NONE, TIMEOUT, HTTP_STATUS, PARSE, NETWORK, POLICY, UNKNOWN }

/**
 * 一次房间检测的结果。
 *
 * 注意：本对象里不保存 Cookie / Token / 设备标识，只有平台公开页面上展示的
 * 主播昵称、直播标题、分区等展示字段。
 */
data class CheckResult(
    val state: LiveState,
    val title: String = "",
    val owner: String = "",
    val sub: String = "",
    val area: String = "",
    val error: String = "",
    /** 平台上报的真实开播时间（本地时区的 epoch millis）；不可信时由引擎过滤。 */
    val liveStartAt: Long? = null,
    /** 数据来源标识，仅用于本地诊断（如 bili-page）。 */
    val src: String = "",
    /** 失败分类，仅用于退避与诊断。 */
    val failure: FailureKind = FailureKind.NONE,
) {
    companion object {
        /** 网络抖动时保持上次状态，仅在界面上提示“网络异常”。 */
        const val SUB_NETWORK_ANOMALY = "网络异常"
    }
}

/** 判定为可靠（可以立即提交状态）的结果。 */
val CheckResult.isReliable: Boolean
    get() = state == LiveState.LIVE || state == LiveState.OFFLINE

/**
 * 用户添加的一个直播间。所有字段都是可持久化的小数据。
 */
data class RoomItem(
    val platform: Platform,
    val id: String,
    val url: String,
    val remark: String = "",
    val autoName: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val notify: Boolean = true,
    /** 持久化的状态键：live / offline / notfound / error / unknown / ""。 */
    val lastStatus: String = "",
    val lastCheckAt: Long? = null,
    val lastLiveAt: Long? = null,
    val lastTitle: String = "",
    val area: String = "",
    /** 轮播等补充状态（如 B站“轮播中”）。 */
    val lastSub: String = "",
    /** 最近一次检测因为网络异常而保持了旧状态。 */
    val lastTransient: Boolean = false,
) {
    val key: String get() = "${platform.key}:$id"

    val displayName: String
        get() = when {
            remark.isNotBlank() -> remark
            autoName.isNotBlank() -> autoName
            else -> id
        }

    companion object {
        const val MAX_REMARK_LEN = 40
        const val MAX_TITLE_LEN = 120
        const val MAX_AREA_LEN = 40
        const val MAX_SUB_LEN = 20
        const val MAX_ID_LEN = 64
    }
}

/** 用户可配置项（移动端默认值偏省电：默认 60 秒检测一次）。 */
data class AppSettings(
    val intervalSec: Int = DEFAULT_INTERVAL_SEC,
    val soundOn: Boolean = true,
    /** 弹窗（通知）显示期间每隔 N 秒温和重复一次提示音；0 = 不循环。 */
    val loopSoundSec: Int = 60,
    /** 提醒通知自动关闭秒数；0 = 常驻直到用户操作。 */
    val popupAutoCloseSec: Int = 0,
    /** App 启动时对已经在直播的房间是否也提醒。 */
    val notifyAlreadyLive: Boolean = true,
    /** 用户是否开启监测（用于进程重启后恢复前台服务）。 */
    val monitorEnabled: Boolean = true,
    /**
     * 严格 TLS 证书固定：只信任平台证书链所属的 CA 家族（GlobalSign/DigiCert），
     * 防止恶意/被攻破 CA 对检测数据做中间人。默认开启；
     * 若平台整体更换 CA 导致所有房间检测失败，可临时关闭。
     */
    val certificatePinning: Boolean = true,
    /**
     * 开播时是否自动打开对应直播间（默认关闭）。
     * 开启后服务在提醒发出后 best-effort 打开：已安装 App 则尝试 App 内房间页，
     * 未安装或失败则用浏览器打开网页版；受 Android 后台启动 Activity 限制影响，
     * 可能被系统拦截，此时用户仍可点通知/列表按钮手动进入。
     */
    val autoOpenRoom: Boolean = false,
    /**
     * 自定义提示音文件（保存在应用内部目录的文件名，空 = 内置提示音）。
     * 只会是应用自己复制进来的固定文件名，不接受外部路径。
     */
    val customSoundFile: String = "",
) {
    fun normalize(): AppSettings = copy(
        intervalSec = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC),
        loopSoundSec = loopSoundSec.coerceIn(0, MAX_LOOP_SEC),
        popupAutoCloseSec = popupAutoCloseSec.coerceIn(0, MAX_AUTO_CLOSE_SEC),
        // 自定义提示音只允许应用内部固定命名，防止配置被手改成任意路径
        customSoundFile = if (CUSTOM_SOUND_PATTERN.matches(customSoundFile)) customSoundFile else "",
    )

    companion object {
        const val MIN_INTERVAL_SEC = 15
        const val MAX_INTERVAL_SEC = 3600
        const val DEFAULT_INTERVAL_SEC = 60
        const val MAX_LOOP_SEC = 3600
        const val MAX_AUTO_CLOSE_SEC = 3600
        const val MAX_ROOMS = 200

        /** 自定义提示音在应用内部目录中的固定文件名（由应用复制，不接受外部路径）。 */
        val CUSTOM_SOUND_PATTERN = Regex("^custom_alert\\.(wav|mp3|ogg|m4a|aac)$")

        /** 合并默认值并做越界修正。 */
        fun of(
            intervalSec: Int? = null,
            soundOn: Boolean? = null,
            loopSoundSec: Int? = null,
            popupAutoCloseSec: Int? = null,
            notifyAlreadyLive: Boolean? = null,
            monitorEnabled: Boolean? = null,
            certificatePinning: Boolean? = null,
            autoOpenRoom: Boolean? = null,
            customSoundFile: String? = null,
        ): AppSettings = AppSettings(
            intervalSec = intervalSec ?: DEFAULT_INTERVAL_SEC,
            soundOn = soundOn ?: true,
            loopSoundSec = loopSoundSec ?: 60,
            popupAutoCloseSec = popupAutoCloseSec ?: 0,
            notifyAlreadyLive = notifyAlreadyLive ?: true,
            monitorEnabled = monitorEnabled ?: true,
            certificatePinning = certificatePinning ?: true,
            autoOpenRoom = autoOpenRoom ?: false,
            customSoundFile = customSoundFile ?: "",
        ).normalize()
    }
}

/** 持久化根对象。 */
data class AppSnapshot(
    val configVersion: Int = CONFIG_VERSION,
    val settings: AppSettings = AppSettings(),
    val rooms: List<RoomItem> = emptyList(),
) {
    companion object {
        const val CONFIG_VERSION = 1
    }
}

/** 列表状态键（持久化值）。 */
object RoomStatusKeys {
    const val LIVE = "live"
    const val OFFLINE = "offline"
    const val NOT_FOUND = "notfound"
    const val ERROR = "error"
    const val UNKNOWN = "unknown"

    val ALL: Set<String> = setOf(LIVE, OFFLINE, NOT_FOUND, ERROR, UNKNOWN)

    fun of(state: LiveState): String = when (state) {
        LiveState.LIVE -> LIVE
        LiveState.OFFLINE -> OFFLINE
        LiveState.NOT_FOUND -> NOT_FOUND
        LiveState.ERROR -> ERROR
        LiveState.UNKNOWN -> UNKNOWN
    }
}
