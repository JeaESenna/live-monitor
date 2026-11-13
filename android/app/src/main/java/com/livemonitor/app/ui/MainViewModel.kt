package com.livemonitor.app.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livemonitor.app.AppGraph
import com.livemonitor.app.R
import com.livemonitor.app.core.jump.RoomJumpIntent
import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.model.AppSnapshot
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.monitor.MonitorControl
import com.livemonitor.app.core.monitor.MonitorRuntime
import com.livemonitor.app.core.notify.AlertSoundPlayer
import com.livemonitor.app.core.notify.CustomSoundStore
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.store.AddRoomResult
import com.livemonitor.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条待展示的 Snackbar 文案（文案统一放在 strings_ui.xml）。 */
data class UiMessage(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
)

/**
 * 主界面 ViewModel。
 *
 * - 只订阅仓储与前台服务的状态（单一数据源），不复制业务状态；
 * - 只持有 Application Context（通过 [AndroidViewModel]），绝不持有 Activity；
 * - 所有动作都转成对 AppRepository / MonitorControl 的调用，网络请求由前台服务完成，
 *   UI 侧不发起任何网络请求。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = AppGraph.repository

    val snapshot: StateFlow<AppSnapshot> = repository.state
    val runtime: StateFlow<MonitorRuntime> = MonitorControl.runtime

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    private var ensuredStart = false

    /**
     * 首次进入界面时恢复监测（仅在“有房间 + 用户开启监测”时启动前台服务）。
     * 用户主动关闭监测后不会被重新拉起。
     */
    fun ensureMonitoringStarted() {
        if (ensuredStart) return
        ensuredStart = true
        val snap = repository.snapshot()
        if (snap.rooms.isEmpty() || !snap.settings.monitorEnabled) return
        MonitorControl.start(appContext)
        MonitorControl.wake(appContext)
    }

    /** 状态条的“暂停监测”：服务保持运行但跳过检测，同时持久化“用户未开启监测”。 */
    fun pauseMonitoring() {
        MonitorControl.setPaused(appContext, true)
        repository.updateSettings { it.copy(monitorEnabled = false) }
    }

    /** 状态条的“继续/启动/开启监测”。 */
    fun resumeMonitoring() {
        repository.updateSettings { it.copy(monitorEnabled = true) }
        MonitorControl.start(appContext)
        MonitorControl.setPaused(appContext, false)
        MonitorControl.wake(appContext)
    }

    /** 设置页“监测总开关”：关闭时停止前台服务。 */
    fun setMonitorEnabled(enabled: Boolean) {
        if (enabled) {
            resumeMonitoring()
        } else {
            repository.updateSettings { it.copy(monitorEnabled = false) }
            MonitorControl.stop(appContext)
        }
    }

    /** 立即检测：服务未运行时先启动，然后唤醒一轮检测。 */
    fun checkNow() {
        if (repository.snapshot().rooms.isEmpty()) return
        if (!MonitorControl.runtime.value.running) {
            repository.updateSettings { it.copy(monitorEnabled = true) }
            MonitorControl.start(appContext)
        }
        MonitorControl.wake(appContext)
    }

    fun setRoomNotify(key: String, enabled: Boolean) = repository.setRoomNotify(key, enabled)

    fun removeRoom(room: RoomItem) {
        repository.removeRoom(room.key)
        postMessage(UiMessage(R.string.ui_deleted_room, listOf(room.displayName)))
    }

    /** 添加直播间；成功后自动开启监测并触发一轮检测。解析与网络都在 UI 之外完成。 */
    fun addRoom(parsed: PlatformApi.ParsedInput, remark: String): AddRoomResult {
        val result = repository.addRoom(parsed.platform, parsed.id, parsed.url, remark)
        if (result is AddRoomResult.Added) {
            repository.updateSettings { it.copy(monitorEnabled = true) }
            MonitorControl.start(appContext)
            MonitorControl.wake(appContext)
            postMessage(UiMessage(R.string.ui_added_room, listOf(result.room.displayName)))
        }
        return result
    }

    fun setIntervalSec(seconds: Int) {
        repository.updateSettings { it.copy(intervalSec = seconds) }
        // 让新的检测间隔立即生效（服务在等待下一轮时会被唤醒重算）。
        if (MonitorControl.runtime.value.running) MonitorControl.wake(appContext)
    }

    fun setSoundOn(enabled: Boolean) = updateSettings { it.copy(soundOn = enabled) }

    fun setLoopSoundSec(seconds: Int) = updateSettings { it.copy(loopSoundSec = seconds) }

    fun setPopupAutoCloseSec(seconds: Int) = updateSettings { it.copy(popupAutoCloseSec = seconds) }

    fun setNotifyAlreadyLive(enabled: Boolean) = updateSettings { it.copy(notifyAlreadyLive = enabled) }

    /** 严格 TLS 证书固定开关：平台整体更换 CA 导致检测全失败时的逃生口。 */
    fun setCertificatePinning(enabled: Boolean) = updateSettings { it.copy(certificatePinning = enabled) }

    /** 开播时自动打开直播间（默认关，由前台服务执行，受系统后台启动限制）。 */
    fun setAutoOpenRoom(enabled: Boolean) = updateSettings { it.copy(autoOpenRoom = enabled) }

    /**
     * 手动进入直播间：只把本机房间 key 传给 [RoomJumpActivity]，
     * Activity 内部再从仓储查房间并交给 RoomLauncher 打开，UI 不拼任何 URL。
     */
    fun openRoom(context: Context, roomKey: String) {
        val intent = RoomJumpIntent.create(context, roomKey)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            postMessage(UiMessage(R.string.ui_jump_open_failed))
        }
    }

    /** 从系统文件选择器导入自定义提示音（SAF，无存储权限；复制到应用内部目录）。 */
    fun importCustomSound(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    CustomSoundStore.importFromUri(appContext, uri)
                } catch (t: Throwable) {
                    CustomSoundStore.ImportResult.Failure(
                        getApplication<Application>().getString(R.string.ui_sound_read_failed),
                    )
                }
            }
            when (result) {
                is CustomSoundStore.ImportResult.Success -> {
                    repository.updateSettings { it.copy(customSoundFile = result.fileName) }
                    postMessage(UiMessage(R.string.ui_sound_import_success))
                }

                is CustomSoundStore.ImportResult.Failure -> {
                    postMessage(UiMessage(R.string.ui_sound_import_failed, listOf(result.reason)))
                }
            }
        }
    }

    /** 恢复内置提示音：删除应用内部的自定义文件并清空设置。 */
    fun restoreDefaultSound() {
        try {
            CustomSoundStore.deleteCustom(appContext)
        } catch (_: Throwable) {
            // 删除失败不阻塞把设置恢复为内置提示音。
        }
        repository.updateSettings { it.copy(customSoundFile = "") }
        postMessage(UiMessage(R.string.ui_sound_restored))
    }

    /** 试听当前提示音（忽略“提示音开关”，但仍尊重系统静音/免打扰）。 */
    fun previewSound() {
        val fileName = repository.snapshot().settings.customSoundFile
        val played = try {
            AlertSoundPlayer.preview(appContext, fileName)
        } catch (_: Throwable) {
            false
        }
        if (!played) postMessage(UiMessage(R.string.ui_sound_preview_failed))
    }

    suspend fun loadLogs(): List<String> = withContext(Dispatchers.IO) { AppLog.recentLines(200) }

    fun clearLogs() = AppLog.clear()

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        repository.updateSettings(transform)
    }

    private fun postMessage(message: UiMessage) {
        _messages.tryEmit(message)
    }
}
