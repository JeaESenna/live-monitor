package com.livemonitor.app.core.store

import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.model.AppSnapshot
import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.model.RoomStatusKeys
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 添加直播间的结果。 */
sealed interface AddRoomResult {
    data class Added(val room: RoomItem) : AddRoomResult
    data class Duplicate(val existing: RoomItem) : AddRoomResult
    data class Invalid(val reason: String) : AddRoomResult
    data object LimitReached : AddRoomResult
}

/**
 * 唯一的本地数据源（Single Source of Truth）。
 *
 * - 状态通过 StateFlow 暴露给 UI 和前台服务；
 * - 修改在锁内完成，持久化串行、异步（conflated channel + IO 线程），
 *   关键路径可调用 [flush] 同步落盘；
 * - 读取时做完整的防御性校验（损坏 → 备份恢复 → 默认值），
 *   与桌面版的“原子写入 + .bak + 坏文件隔离”思路一致。
 */
class AppRepository(
    private val kv: KvStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val stateLock = Any()
    private val persistLock = Any()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSnapshot> = _state.asStateFlow()

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        persistScope.launch {
            for (unused in saveRequests) {
                persistBlocking()
            }
        }
    }

    fun snapshot(): AppSnapshot = _state.value

    // ---------------- 直播间操作 ----------------

    fun addRoom(platform: Platform, id: String, url: String, remark: String): AddRoomResult {
        val cleanId = id.trim()
        if (cleanId.isEmpty() || cleanId.length > RoomItem.MAX_ID_LEN || !ID_PATTERN.matches(cleanId)) {
            return AddRoomResult.Invalid("房间号格式不正确，只允许字母、数字、下划线和短横线")
        }
        val canonicalUrl = PlatformApi.normalizeStoredUrl(platform, cleanId, url)
            ?: return AddRoomResult.Invalid("直播间地址无效，请检查链接或房间号")
        val cleanRemark = sanitizeText(remark, RoomItem.MAX_REMARK_LEN)
        val newRoom = RoomItem(
            platform = platform,
            id = cleanId,
            url = canonicalUrl,
            remark = cleanRemark,
            addedAt = clock(),
        )
        var result: AddRoomResult = AddRoomResult.Invalid("未知错误")
        mutate { old ->
            when {
                old.rooms.size >= AppSettings.MAX_ROOMS -> {
                    result = AddRoomResult.LimitReached
                    old
                }

                old.rooms.any { it.key.equals(newRoom.key, ignoreCase = true) } -> {
                    result = AddRoomResult.Duplicate(old.rooms.first { it.key.equals(newRoom.key, ignoreCase = true) })
                    old
                }

                else -> {
                    result = AddRoomResult.Added(newRoom)
                    old.copy(rooms = old.rooms + newRoom)
                }
            }
        }
        return result
    }

    fun removeRoom(key: String) {
        mutate { old -> old.copy(rooms = old.rooms.filterNot { it.key == key }) }
    }

    fun setRoomNotify(key: String, enabled: Boolean) {
        updateRoom(key) { it.copy(notify = enabled) }
    }

    /** 原子地更新单个房间；不存在时 no-op。 */
    fun updateRoom(key: String, transform: (RoomItem) -> RoomItem) {
        mutate { old ->
            val idx = old.rooms.indexOfFirst { it.key == key }
            if (idx < 0) {
                old
            } else {
                val updated = sanitizeRoom(transform(old.rooms[idx]))
                if (updated == old.rooms[idx]) {
                    old
                } else {
                    old.copy(rooms = old.rooms.toMutableList().also { it[idx] = updated })
                }
            }
        }
    }

    // ---------------- 设置操作 ----------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutate { old ->
            val updated = transform(old.settings).normalize()
            if (updated == old.settings) old else old.copy(settings = updated)
        }
    }

    // ---------------- 持久化 ----------------

    /** 请求异步落盘（合并短时间内的多次修改）。 */
    fun requestSave() {
        saveRequests.trySend(Unit)
    }

    /** 同步落盘：用于 Activity/Service 进入后台或销毁前，确保不丢数据。 */
    fun flushBlocking() = persistBlocking()

    suspend fun flush() = withContext(Dispatchers.IO) { persistBlocking() }

    private fun mutate(transform: (AppSnapshot) -> AppSnapshot) {
        synchronized(stateLock) {
            val before = _state.value
            val after = transform(before)
            if (after != before) {
                _state.value = after
                saveRequests.trySend(Unit)
            }
        }
    }

    private fun persistBlocking() {
        synchronized(persistLock) {
            try {
                val snapshot = _state.value
                val json = encode(snapshot)
                val old = kv.getString(KEY_STATE)
                val pairs = buildMap {
                    if (old != null) put(KEY_BACKUP, old)
                    put(KEY_STATE, json)
                }
                if (!kv.putStrings(pairs)) {
                    AppLog.error("Store", "persist failed")
                }
            } catch (t: Throwable) {
                AppLog.error("Store", "persist error", t)
            }
        }
    }

    // ---------------- 加载 / 修复 ----------------

    private fun load(): AppSnapshot {
        val primary = kv.getString(KEY_STATE)
        if (primary != null) {
            val decoded = decodeSafely(primary, "primary")
            if (decoded != null) return decoded
            AppLog.error("Store", "state corrupted, trying backup")
        }
        val backup = kv.getString(KEY_BACKUP)
        if (backup != null) {
            val decoded = decodeSafely(backup, "backup")
            if (decoded != null) {
                AppLog.info("Store", "recovered from backup")
                return decoded
            }
        }
        return AppSnapshot()
    }

    private fun decodeSafely(raw: String, source: String): AppSnapshot? = try {
        val decoded = decode(raw)
        if (decoded.rooms.size > AppSettings.MAX_ROOMS) {
            AppLog.error("Store", "too many rooms in $source, truncated")
            decoded.copy(rooms = decoded.rooms.take(AppSettings.MAX_ROOMS))
        } else {
            decoded
        }
    } catch (t: Throwable) {
        AppLog.error("Store", "decode failed ($source)", t)
        null
    }

    private fun encode(snapshot: AppSnapshot): String {
        val root = JSONObject()
        root.put("configVersion", AppSnapshot.CONFIG_VERSION)
        root.put(
            "settings",
            JSONObject().apply {
                put("intervalSec", snapshot.settings.intervalSec)
                put("soundOn", snapshot.settings.soundOn)
                put("loopSoundSec", snapshot.settings.loopSoundSec)
                put("popupAutoCloseSec", snapshot.settings.popupAutoCloseSec)
                put("notifyAlreadyLive", snapshot.settings.notifyAlreadyLive)
                put("monitorEnabled", snapshot.settings.monitorEnabled)
                put("certificatePinning", snapshot.settings.certificatePinning)
                put("autoOpenRoom", snapshot.settings.autoOpenRoom)
                put("customSoundFile", snapshot.settings.customSoundFile)
            },
        )
        root.put(
            "rooms",
            JSONArray().apply {
                for (room in snapshot.rooms) put(roomToJson(room))
            },
        )
        return root.toString()
    }

    private fun roomToJson(room: RoomItem): JSONObject = JSONObject().apply {
        put("platform", room.platform.key)
        put("id", room.id)
        put("url", room.url)
        put("remark", room.remark)
        put("autoName", room.autoName)
        put("addedAt", room.addedAt)
        put("notify", room.notify)
        put("lastStatus", room.lastStatus)
        put("lastCheckAt", room.lastCheckAt ?: JSONObject.NULL)
        put("lastLiveAt", room.lastLiveAt ?: JSONObject.NULL)
        put("lastTitle", room.lastTitle)
        put("area", room.area)
        put("lastSub", room.lastSub)
        put("lastTransient", room.lastTransient)
    }

    private fun decode(raw: String): AppSnapshot {
        val root = JSONObject(raw)
        val settingsJson = root.optJSONObject("settings")
        val settings = AppSettings.of(
            intervalSec = settingsJson?.optIntOrNull("intervalSec"),
            soundOn = settingsJson?.optBooleanOrNull("soundOn"),
            loopSoundSec = settingsJson?.optIntOrNull("loopSoundSec"),
            popupAutoCloseSec = settingsJson?.optIntOrNull("popupAutoCloseSec"),
            notifyAlreadyLive = settingsJson?.optBooleanOrNull("notifyAlreadyLive"),
            monitorEnabled = settingsJson?.optBooleanOrNull("monitorEnabled"),
            certificatePinning = settingsJson?.optBooleanOrNull("certificatePinning"),
            autoOpenRoom = settingsJson?.optBooleanOrNull("autoOpenRoom"),
            customSoundFile = settingsJson?.optString("customSoundFile").orEmpty(),
        )
        val roomsJson = root.optJSONArray("rooms") ?: JSONArray()
        val rooms = ArrayList<RoomItem>(minOf(roomsJson.length(), AppSettings.MAX_ROOMS))
        val seen = HashSet<String>()
        for (i in 0 until roomsJson.length()) {
            if (rooms.size >= AppSettings.MAX_ROOMS) break
            val obj = roomsJson.optJSONObject(i) ?: continue
            val room = decodeRoom(obj) ?: continue
            if (!seen.add(room.key.lowercase())) continue
            rooms.add(room)
        }
        return AppSnapshot(
            configVersion = root.optInt("configVersion", AppSnapshot.CONFIG_VERSION),
            settings = settings,
            rooms = rooms,
        )
    }

    private fun decodeRoom(obj: JSONObject): RoomItem? {
        val platform = Platform.fromKey(obj.optString("platform")) ?: return null
        val id = obj.optString("id").trim()
        if (id.isEmpty() || id.length > RoomItem.MAX_ID_LEN || !ID_PATTERN.matches(id)) return null
        val url = PlatformApi.normalizeStoredUrl(platform, id, obj.optString("url")) ?: return null
        val lastStatus = obj.optString("lastStatus")
        return sanitizeRoom(
            RoomItem(
                platform = platform,
                id = id,
                url = url,
                remark = sanitizeText(obj.optString("remark"), RoomItem.MAX_REMARK_LEN),
                autoName = sanitizeText(obj.optString("autoName"), RoomItem.MAX_REMARK_LEN),
                addedAt = obj.optNullableLong("addedAt") ?: clock(),
                notify = obj.optBooleanOrNull("notify") ?: true,
                lastStatus = if (lastStatus in RoomStatusKeys.ALL) lastStatus else "",
                lastCheckAt = obj.optNullableLong("lastCheckAt"),
                lastLiveAt = obj.optNullableLong("lastLiveAt"),
                lastTitle = sanitizeText(obj.optString("lastTitle"), RoomItem.MAX_TITLE_LEN),
                area = sanitizeText(obj.optString("area"), RoomItem.MAX_AREA_LEN),
                lastSub = sanitizeText(obj.optString("lastSub"), RoomItem.MAX_SUB_LEN),
                lastTransient = obj.optBooleanOrNull("lastTransient") ?: false,
            ),
        )
    }

    private fun sanitizeRoom(room: RoomItem): RoomItem = room.copy(
        remark = sanitizeText(room.remark, RoomItem.MAX_REMARK_LEN),
        autoName = sanitizeText(room.autoName, RoomItem.MAX_REMARK_LEN),
        lastTitle = sanitizeText(room.lastTitle, RoomItem.MAX_TITLE_LEN),
        area = sanitizeText(room.area, RoomItem.MAX_AREA_LEN),
        lastSub = sanitizeText(room.lastSub, RoomItem.MAX_SUB_LEN),
        lastStatus = if (room.lastStatus in RoomStatusKeys.ALL) room.lastStatus else "",
    )

    companion object {
        private const val KEY_STATE = "state_v1"
        private const val KEY_BACKUP = "state_v1.bak"
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * 清洗用户/平台文本：去掉所有控制字符（防通知/界面注入）、
         * 压缩空白、裁剪到上限。
         */
        fun sanitizeText(input: String?, maxChars: Int): String {
            if (input.isNullOrEmpty()) return ""
            val sb = StringBuilder(minOf(input.length, maxChars))
            var lastWasSpace = false
            for (ch in input) {
                if (sb.length >= maxChars) break
                val isSpace = ch.isWhitespace()
                if (isSpace) {
                    if (!lastWasSpace && sb.isNotEmpty()) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                } else if (ch.code >= 0x20 && ch.code != 0x7f && ch.code !in 0x80..0x9f) {
                    sb.append(ch)
                    lastWasSpace = false
                }
            }
            return sb.toString().trim()
        }
    }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (isNull(key)) return null
    val value = optLong(key, 0L)
    return if (value > 0L) value else null
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
