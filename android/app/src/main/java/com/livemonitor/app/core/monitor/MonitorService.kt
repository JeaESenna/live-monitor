package com.livemonitor.app.core.monitor

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.livemonitor.app.AppGraph
import com.livemonitor.app.core.jump.RoomLauncher
import com.livemonitor.app.core.model.CheckResult
import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.model.RoomStatusKeys
import com.livemonitor.app.core.notify.AlertNotifier
import com.livemonitor.app.core.notify.AlertPolicy
import com.livemonitor.app.core.notify.NotificationHelper
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.platform.PlatformHttp
import com.livemonitor.app.core.store.AppRepository
import com.livemonitor.app.util.AppLog
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 前台监测服务（移动端只保留“开播提醒”：不跳转直播间、不打开浏览器）。
 *
 * 与桌面版 `RoomMonitor` 的对应关系：
 * - 单协程顺序轮询所有房间（房间之间 120ms + 0~2s 随机抖动，避免同一时刻打点）；
 * - 检测结果交给 [MonitorEngine] 状态机（可靠结果立即提交，不可靠需连续 2 次），
 *   再由本服务写回 [AppRepository]；每轮结束 `requestSave()`；
 * - 开播时通过 [AlertNotifier] 发本地通知 + 提示音/循环提示音；
 * - 暂停/继续/立即检测/停止 都由显式 Intent 命令驱动（见 [MonitorControl]）。
 *
 * 省电/稳定性决策：
 * - 等待期间只 `delay`/挂起，不持有唤醒锁、不轮询 CPU；房间列表为空时 2s 轻量等待；
 * - 每个房间检测前短暂持有 PARTIAL_WAKE_LOCK（10s 超时），检测完立即在 finally 释放，
 *   不跨轮长持；屏息期间也能完成一次紧凑的网络轮询；
 * - 无网络（activeNetwork == null）直接跳过本轮，只更新运行状态，零网络请求；
 * - 连续网络失败按 interval × min(2^n,4) 指数退避，成功后立即恢复原间隔；
 * - 检测间隔/提示音等设置每轮从仓储重新读取，设置变更会唤醒重排；
 * - START_STICKY：进程被系统回收后恢复监测；Android 12+ 后台启动前台服务受限时
 *   捕获异常并安全退出（stopSelf），绝不因为 startForeground 失败而崩溃。
 */
class MonitorService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    /** 唤醒信号（CONFLATED：连续多次“立即检测”合并成一次，不会积压成一串检测）。 */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private lateinit var repository: AppRepository
    private lateinit var engine: MonitorEngine
    private lateinit var alertNotifier: AlertNotifier

    private var engineJob: Job? = null
    private var settingsJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var paused = false
    private var stopping = false
    private var networkFailureStreak = 0
    private var lastRoundAt: Long? = null

    /** 是否已有一个“自动打开直播间”在等待执行（同一时间只自动打开一个，避免连续弹多个 Activity）。 */
    private var autoOpenPending = false

    /** 本次服务进程内已经完成首次检测的房间（用于 notifyAlreadyLive 语义）。 */
    private val firstSeenKeys = HashSet<String>()

    /** 上一轮见到的房间 key，用于清理已删除房间的提醒与状态机缓存。 */
    private val knownRoomKeys = HashSet<String>()

    private val random = Random.Default

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        try {
            repository = AppGraph.repository
        } catch (t: Throwable) {
            AppLog.error("Service", "repository unavailable", t)
            stopSelf()
            return
        }
        engine = MonitorEngine()
        alertNotifier = AlertNotifier(this)
        NotificationHelper.createChannels(this)
        wakeLock = try {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false) }
        } catch (t: Throwable) {
            AppLog.error("Service", "wake lock unavailable", t)
            null
        }
        MonitorRuntimeHolder.update {
            MonitorRuntime(running = true, paused = false, startedAt = System.currentTimeMillis())
        }
        settingsJob = serviceScope.launch { observeSettings() }
        AppLog.info("Service", "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!this::repository.isInitialized || !this::alertNotifier.isInitialized) {
            // 初始化失败（极端情况）：安全退出，不进入前台服务状态。
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val action = intent?.action

        if (stopping) {
            // 服务正在停止（已调用 stopSelf）：忽略迟到的命令，避免在“半停止”状态再启动引擎。
            // 用户在停止后重新开启监测时，系统会创建新的 Service 实例。
            return START_NOT_STICKY
        }

        // 只有 ACTION_START（以及系统 START_STICKY 的 null 重启）需要进入前台服务状态；
        // 其它命令由 UI/通知动作通过普通 startService 发送，不能在这里调用 startForeground
        // （否则“服务已停止时点通知动作”会触发 5s 内必须 startForeground 的崩溃路径）。
        if (!foregroundStarted && action != null && action != MonitorControl.ACTION_START) {
            return handleCommandWhileNotForeground(action, intent)
        }

        when (action) {
            MonitorControl.ACTION_STOP -> {
                if (!ensureForeground()) return START_NOT_STICKY
                stopMonitoring(userStopped = true)
                return START_NOT_STICKY
            }

            MonitorControl.ACTION_SET_PAUSED -> {
                if (!ensureForeground()) return START_NOT_STICKY
                setPaused(intent?.getBooleanExtra(MonitorControl.EXTRA_PAUSED, false) == true)
            }

            MonitorControl.ACTION_DISMISS_ALERT,
            MonitorControl.ACTION_MUTE_ALERT,
            MonitorControl.ACTION_ALERT_DISMISSED -> {
                if (!ensureForeground()) return START_NOT_STICKY
                handleAlertAction(intent, mute = action == MonitorControl.ACTION_MUTE_ALERT)
            }

            MonitorControl.ACTION_WAKE -> {
                if (!ensureForeground()) return START_NOT_STICKY
                startEngine()
                wakeSignal.trySend(Unit)
            }

            MonitorControl.ACTION_START, null -> {
                // ACTION_START：用户开启监测；null：系统按 START_STICKY 重启服务。
                if (!ensureForeground()) return START_NOT_STICKY
                if (action == null && !repository.state.value.settings.monitorEnabled) {
                    // 用户已经关闭监测，系统重启的服务不应悄悄恢复：优雅退出。
                    stopMonitoring(userStopped = false)
                    return START_NOT_STICKY
                }
                if (action == MonitorControl.ACTION_START &&
                    !repository.state.value.settings.monitorEnabled
                ) {
                    repository.updateSettings { it.copy(monitorEnabled = true) }
                    repository.requestSave()
                }
                startEngine()
                wakeSignal.trySend(Unit)
            }

            else -> {
                // 未知 action：保持服务运行，忽略即可。
                if (!ensureForeground()) return START_NOT_STICKY
                AppLog.info("Service", "unknown action ignored")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        engineJob?.cancel()
        engineJob = null
        settingsJob?.cancel()
        settingsJob = null
        serviceJob.cancel()
        releaseWakeLock()
        if (::alertNotifier.isInitialized) {
            try {
                alertNotifier.shutdown()
            } catch (t: Throwable) {
                AppLog.error("Service", "notifier shutdown failed", t)
            }
        }
        if (::repository.isInitialized) {
            try {
                // 同步落盘：进程可能马上被回收，不能让最后的检测状态丢失。
                repository.flushBlocking()
            } catch (t: Throwable) {
                AppLog.error("Service", "flush on destroy failed", t)
            }
        }
        MonitorRuntimeHolder.reset()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            AppLog.error("Service", "stopForeground failed", t)
        }
        try {
            notificationManager?.cancel(NotificationHelper.FOREGROUND_NOTIFICATION_ID)
        } catch (t: Throwable) {
            AppLog.error("Service", "cancel foreground notification failed", t)
        }
        AppLog.info("Service", "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 命令处理 ----------------

    /**
     * 服务不在前台时收到“非 START”命令（例如进程被系统重建后用户点通知动作）：
     * 只做尽力而为的清理（取消提醒通知），绝不调用 startForeground，然后安全退出。
     */
    private fun handleCommandWhileNotForeground(action: String, intent: Intent?): Int {
        when (action) {
            MonitorControl.ACTION_STOP -> {
                persistMonitorEnabled(false)
                flushSafely()
            }

            MonitorControl.ACTION_DISMISS_ALERT,
            MonitorControl.ACTION_MUTE_ALERT,
            MonitorControl.ACTION_ALERT_DISMISSED -> {
                val roomKey = intent?.getStringExtra(MonitorControl.EXTRA_ROOM_KEY)
                if (!roomKey.isNullOrEmpty()) {
                    NotificationHelper.cancelAlertNotification(this, roomKey)
                }
            }

            else -> Unit // 暂停/立即检测等命令在服务未运行时没有意义
        }
        AppLog.info("Service", "command handled while not foreground: ${action.substringAfterLast('.')}")
        MonitorRuntimeHolder.reset()
        stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun handleAlertAction(intent: Intent?, mute: Boolean) {
        val roomKey = intent?.getStringExtra(MonitorControl.EXTRA_ROOM_KEY) ?: return
        if (mute) {
            alertNotifier.muteAlert(roomKey)
        } else {
            alertNotifier.dismissAlert(roomKey)
        }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        startEngine()
        MonitorRuntimeHolder.update { it.copy(running = true, paused = value) }
        // 暂停时立即停止循环提示音（提醒通知保留），继续后恢复循环。
        alertNotifier.setPaused(value)
        updateForegroundNotification()
        // 暂停/继续都要唤醒等待中的循环：暂停后立即停止轮询，继续后尽快检测一轮。
        wakeSignal.trySend(Unit)
        AppLog.info("Service", "paused=$value")
    }

    private fun stopMonitoring(userStopped: Boolean) {
        if (stopping) return
        stopping = true
        AppLog.info("Service", if (userStopped) "stop requested (user)" else "stop requested (system)")
        engineJob?.cancel()
        engineJob = null
        settingsJob?.cancel()
        settingsJob = null
        releaseWakeLock()
        if (::alertNotifier.isInitialized) {
            try {
                alertNotifier.cancelAll()
            } catch (t: Throwable) {
                AppLog.error("Service", "cancel alerts failed", t)
            }
        }
        if (userStopped) {
            persistMonitorEnabled(false)
        }
        flushSafely()
        MonitorRuntimeHolder.reset()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            AppLog.error("Service", "stopForeground failed", t)
        }
        foregroundStarted = false
        stopSelfSafely()
    }

    // ---------------- 前台服务通知 ----------------

    /**
     * 进入（或刷新）前台服务状态。
     * @return false 表示无法进入前台（权限/系统限制），调用方应安全退出。
     */
    private fun ensureForeground(): Boolean {
        if (foregroundStarted) {
            updateForegroundNotification()
            return true
        }
        return try {
            val notification = NotificationHelper.buildForegroundNotification(this, currentStatus())
            // Android 14+ 必须在 startForeground 时声明类型（Manifest 已声明 specialUse）。
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            AppLog.info("Service", "foreground started")
            true
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务限制 / 通知权限被撤销 / 少数 ROM 异常：
            // 安全退出，绝不让系统因“未在 5 秒内调用 startForeground”而崩溃。
            AppLog.error("Service", "startForeground failed", t)
            stopSelfSafely()
            false
        }
    }

    private fun updateForegroundNotification() {
        if (!foregroundStarted || stopping) return
        try {
            notificationManager?.notify(
                NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                NotificationHelper.buildForegroundNotification(this, currentStatus()),
            )
        } catch (t: Throwable) {
            AppLog.error("Service", "update foreground notification failed", t)
        }
    }

    private fun currentStatus(): NotificationHelper.ServiceStatus {
        val snapshot = repository.state.value
        return NotificationHelper.ServiceStatus(
            paused = paused,
            roomCount = snapshot.rooms.size,
            liveCount = snapshot.rooms.count { it.lastStatus == RoomStatusKeys.LIVE },
            lastCheckAt = lastRoundAt,
        )
    }

    // ---------------- 轮询引擎 ----------------

    private fun startEngine() {
        if (stopping || !foregroundStarted) return
        if (engineJob?.isActive != true) {
            engineJob = serviceScope.launch { engineLoop() }
        }
        if (settingsJob?.isActive != true) {
            settingsJob = serviceScope.launch { observeSettings() }
        }
    }

    /** 设置变化实时生效：提示音开关立即作用，并唤醒等待让新的检测间隔尽快生效。 */
    private suspend fun observeSettings() {
        try {
            repository.state.map { it.settings }.distinctUntilChanged().collect { settings ->
                try {
                    // 证书固定开关对后续检测立即生效（检测器会换用对应 OkHttpClient）。
                    PlatformHttp.pinningEnabled = settings.certificatePinning
                    alertNotifier.syncSettings(settings)
                } catch (t: Throwable) {
                    AppLog.error("Service", "apply settings failed", t)
                }
                wakeSignal.trySend(Unit)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.error("Service", "settings observer failed", t)
        }
    }

    private suspend fun engineLoop() {
        try {
            // 与桌面版 1.5s 启动延迟接近：先让常驻通知/App 界面就绪，避免启动瞬间抢网络。
            delay(INITIAL_DELAY_MS)
            while (currentCoroutineContext().isActive && !stopping) {
                try {
                    if (paused) {
                        // 暂停：不轮询、不持有任何资源，挂起等待“继续/设置变更/停止”。
                        wakeSignal.receive()
                        continue
                    }
                    runCheckRoundSafely()
                    updateForegroundNotification()
                    // 本轮已覆盖“立即检测”的语义：丢弃检测期间积压的唤醒信号，
                    // 避免紧接着又跑一整轮（并保持 CONFLATED 通道精简）。
                    while (wakeSignal.tryReceive().isSuccess) {
                        // drain
                    }
                    val waitMs = nextWaitMillis()
                    withTimeoutOrNull(waitMs) { wakeSignal.receive() }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 单轮异常不能让监测静默停止：记录后短暂等待继续。
                    AppLog.error("Service", "round failed", t)
                    MonitorRuntimeHolder.update { it.copy(checking = false, lastError = "监测循环异常") }
                    delay(ERROR_RETRY_MS)
                }
            }
        } finally {
            MonitorRuntimeHolder.update { it.copy(checking = false) }
        }
    }

    /** 下一轮等待毫秒数：空列表 2s 轻量等待；否则 interval × 网络失败退避倍率。 */
    private fun nextWaitMillis(): Long {
        val snapshot = repository.state.value
        if (snapshot.rooms.isEmpty()) return EMPTY_ROOM_WAIT_MS
        return AlertPolicy.nextCheckDelayMillis(snapshot.settings.intervalSec, networkFailureStreak)
    }

    private suspend fun runCheckRoundSafely() {
        val rooms = repository.state.value.rooms
        if (rooms.isEmpty()) {
            // 空列表：零网络、零 CPU，仅保持运行状态；2s 后重新读取设置。
            MonitorRuntimeHolder.update {
                it.copy(running = true, paused = paused, checking = false, liveCount = 0, lastError = "")
            }
            return
        }
        MonitorRuntimeHolder.update { it.copy(running = true, paused = paused, checking = true) }
        try {
            if (!hasInternet()) {
                // 无网络：跳过本轮（零请求），只更新运行状态与错误提示。
                networkFailureStreak = (networkFailureStreak + 1).coerceAtMost(MAX_FAILURE_STREAK)
                MonitorRuntimeHolder.update {
                    it.copy(checking = false, lastError = "无网络连接，已跳过本轮")
                }
                AppLog.info("Service", "round skipped: no network")
                return
            }
            val stats = checkRooms(rooms)
            if (stats.retryableFailures > 0 && stats.successes == 0) {
                networkFailureStreak = (networkFailureStreak + 1).coerceAtMost(MAX_FAILURE_STREAK)
            } else {
                networkFailureStreak = 0
            }
            val now = System.currentTimeMillis()
            lastRoundAt = now
            val liveCount = repository.state.value.rooms.count { it.lastStatus == RoomStatusKeys.LIVE }
            val allFailed = stats.retryableFailures > 0 && stats.successes == 0
            MonitorRuntimeHolder.update {
                it.copy(
                    running = true,
                    paused = paused,
                    checking = false,
                    lastCheckAt = now,
                    liveCount = liveCount,
                    lastError = if (allFailed) "网络异常，正在退避重试" else "",
                )
            }
            // 清理用户在通知栏点开/清空的提醒（没人能保证 deleteIntent 一定回调）。
            alertNotifier.sweep()
        } finally {
            // 每轮结束都落盘一次：异常中断也不会丢已检测的状态。
            try {
                repository.requestSave()
            } catch (t: Throwable) {
                AppLog.error("Service", "requestSave failed", t)
            }
            MonitorRuntimeHolder.update { it.copy(checking = false) }
        }
    }

    private class RoundStats(
        var successes: Int = 0,
        var retryableFailures: Int = 0,
    )

    private suspend fun checkRooms(rooms: List<RoomItem>): RoundStats {
        val stats = RoundStats()
        // 清理本轮之前已被用户删除的房间：状态机缓存 + 提醒 + mute。
        val currentKeys = rooms.mapTo(HashSet()) { it.key }
        val removedKeys = knownRoomKeys.filter { it !in currentKeys }
        for (key in removedKeys) {
            engine.removeState(key)
            firstSeenKeys.remove(key)
            alertNotifier.forgetRoom(key)
        }
        knownRoomKeys.clear()
        knownRoomKeys.addAll(currentKeys)

        for (room in rooms) {
            if (paused || stopping || !currentCoroutineContext().isActive) return stats
            // 用户可能在检测过程中删除了该房间（已删除就不再写回/提醒）。
            if (repository.state.value.rooms.none { it.key == room.key }) continue

            // 防同时打点：120ms 固定间隔 + 0~2s 随机抖动（桌面版单线程 120ms 的移动端等效）。
            delay(ROOM_GAP_MS + random.nextLong(ROOM_JITTER_MAX_MS))

            val raw = checkOne(room)
            if (raw == null) {
                stats.retryableFailures++
                continue
            }

            val outcome = try {
                engine.process(room, raw)
            } catch (t: Throwable) {
                AppLog.error("Service", "engine process failed platform=${room.platform.key} room=${room.id}", t)
                continue
            }
            persistOutcome(room, outcome)
            maybeNotify(room, outcome)
            if (AlertPolicy.isRetryableFailure(raw)) {
                stats.retryableFailures++
            } else {
                stats.successes++
            }
        }
        return stats
    }

    /**
     * 检测单个房间。每个房间检测前短暂持有 PARTIAL_WAKE_LOCK（10s 超时），
     * 无论成功/异常都在 finally 立即释放，绝不让锁跨轮存活。
     */
    private fun checkOne(room: RoomItem): CheckResult? {
        acquireWakeLock()
        return try {
            val raw = try {
                PlatformApi.check(room.platform, room.id, room.url)
            } catch (t: Throwable) {
                // 平台模块约定不抛异常，这里做最终兜底：失败分类只用于退避，不泄露内容。
                AppLog.error("Service", "check threw platform=${room.platform.key} room=${room.id}", t)
                CheckResult(state = LiveState.ERROR, error = "检测异常", failure = FailureKind.UNKNOWN)
            }
            val now = System.currentTimeMillis()
            raw.copy(liveStartAt = PlatformApi.sanitizeLiveStartAt(raw.liveStartAt, now))
        } finally {
            releaseWakeLock()
        }
    }

    /**
     * 把状态机结果写回仓储（与桌面版 HandleEvent 的记录规则一致）：
     * lastStatus / lastTransient / lastTitle / area / lastSub / lastLiveAt / lastCheckAt / autoName。
     */
    private fun persistOutcome(room: RoomItem, outcome: MonitorEngine.Outcome) {
        try {
            val result = outcome.result
            val now = System.currentTimeMillis()
            repository.updateRoom(room.key) { old ->
                var updated = old.copy(
                    lastStatus = outcome.persistedStatus,
                    lastTransient = outcome.transient,
                    lastCheckAt = now,
                )
                // 网络异常的一轮不覆盖标题/分区/轮播状态，避免把已有信息清空。
                if (!outcome.transient && result.sub != CheckResult.SUB_NETWORK_ANOMALY) {
                    updated = updated.copy(lastSub = result.sub)
                }
                if (result.state != LiveState.ERROR && result.area.isNotEmpty()) {
                    updated = updated.copy(area = result.area)
                }
                if (result.title.isNotEmpty() && result.sub != CheckResult.SUB_NETWORK_ANOMALY) {
                    updated = updated.copy(lastTitle = result.title)
                }
                updated = updated.copy(lastLiveAt = resolveLastLiveAt(old, result, outcome))
                // 首次拿到主播昵称且用户没写备注时自动补全显示名。
                if (updated.autoName.isEmpty() && updated.remark.isEmpty() && result.owner.isNotEmpty()) {
                    updated = updated.copy(autoName = result.owner)
                }
                updated
            }
            repository.requestSave()
        } catch (t: Throwable) {
            // 写回失败只记日志：监测/提醒不能因为一次存储异常中断。
            AppLog.error("Service", "persist failed platform=${room.platform.key} room=${room.id}", t)
        }
    }

    /**
     * “上次开播”时间（桌面版规则）：
     * - 本次确认开播（becameLive）→ 优先平台真实开播时间，否则用检测到的时间；
     * - 仍在直播：平台时间与已记录时间相差 >10 分钟才纠正（避免平台抖动导致时间跳变）；
     * - 平台完全不上报开播时间且从未记录过 → 用检测时间兜底，保证列表不会显示空。
     */
    private fun resolveLastLiveAt(
        old: RoomItem,
        result: CheckResult,
        outcome: MonitorEngine.Outcome,
    ): Long? {
        if (outcome.becameLive) return result.liveStartAt ?: System.currentTimeMillis()
        if (result.state != LiveState.LIVE) return old.lastLiveAt
        val platformStart = result.liveStartAt
        if (platformStart != null) {
            val last = old.lastLiveAt ?: return platformStart
            return if (kotlin.math.abs(last - platformStart) > LIVE_START_CORRECTION_MS) {
                platformStart
            } else {
                last
            }
        }
        return old.lastLiveAt ?: System.currentTimeMillis()
    }

    /** 开播提醒判定与发送（冷却由 MonitorEngine 负责）。 */
    private fun maybeNotify(room: RoomItem, outcome: MonitorEngine.Outcome) {
        val settings = repository.state.value.settings
        val result = outcome.result
        // 可靠下播：解除“不再提醒”，本次开播结束。
        if (result.state == LiveState.OFFLINE && !outcome.transient) {
            alertNotifier.onReliableOffline(room.key)
        }
        val firstCheck = firstSeenKeys.add(room.key)
        if (!outcome.becameLive) return
        // 用仓储里最新的房间信息：本轮检测期间用户可能关掉了该房间的提醒。
        val latest = repository.state.value.rooms.firstOrNull { it.key == room.key } ?: return
        if (!AlertPolicy.shouldAlert(
                becameLive = outcome.becameLive,
                roomNotify = latest.notify,
                muted = alertNotifier.isMuted(latest.key),
                firstCheck = firstCheck,
                notifyAlreadyLive = settings.notifyAlreadyLive,
            )
        ) {
            return
        }
        val posted = alertNotifier.showLiveAlert(latest, result, settings)
        AppLog.info("Service", "alert shown=$posted platform=${room.platform.key} room=${room.id}")
        if (posted && settings.autoOpenRoom) {
            scheduleAutoOpen(latest)
        }
    }

    /**
     * “开播时自动打开直播间”（设置默认关闭）：提醒成功发出后 3 秒，在**独立协程**里
     * best-effort 调用 [RoomLauncher.autoOpen]（App 优先、未安装/失败用浏览器）。
     *
     * 限制与安全：
     * - Android 10+ 后台启动 Activity 受限（BAL），系统可能静默拦截，无法可靠感知；
     *   startActivity 的异常（SecurityException/ActivityNotFound/AndroidRuntimeException）全部
     *   try/catch，只记录一行 “auto open blocked”，绝不影响监测循环；
     * - 每收到一条提醒最多尝试一次；同一时间只排队一个自动打开，避免同时开播多路时连弹多个
     *   Activity（其余提醒用户可点通知“进入直播间”）；
     * - 执行前再次读取设置，用户在这 3 秒内关掉开关则不打开；
     * - 不记录 URL，只记录平台 key/房间 id 与结果枚举。
     */
    private fun scheduleAutoOpen(room: RoomItem) {
        if (stopping || paused || autoOpenPending) return
        autoOpenPending = true
        serviceScope.launch {
            try {
                delay(AUTO_OPEN_DELAY_MS)
                if (stopping || paused) return@launch
                if (!repository.state.value.settings.autoOpenRoom) return@launch
                val result = try {
                    RoomLauncher.autoOpen(this@MonitorService, room.platform, room.id, room.url)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 后台启动 Activity 被系统限制 / 平台 App 异常：只记录，不重试、不刷屏。
                    AppLog.info(
                        "Service",
                        "auto open blocked platform=${room.platform.key} room=${room.id} reason=${t.javaClass.simpleName}",
                    )
                    return@launch
                }
                AppLog.info(
                    "Service",
                    "auto open platform=${room.platform.key} room=${room.id} result=$result",
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.info("Service", "auto open blocked reason=${t.javaClass.simpleName}")
            } finally {
                autoOpenPending = false
            }
        }
    }

    // ---------------- 网络 / 唤醒锁 ----------------

    /** 无网络时跳过本轮：activeNetwork 为空或没有 INTERNET 能力都视为不可用（零请求）。 */
    private fun hasInternet(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            // 查询失败时保守地尝试检测，不要因为系统 API 异常让监测停摆。
            AppLog.error("Service", "network state check failed", t)
            true
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            AppLog.error("Service", "wake lock acquire failed", t)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            AppLog.error("Service", "wake lock release failed", t)
        }
    }

    // ---------------- 收尾工具 ----------------

    private fun persistMonitorEnabled(enabled: Boolean) {
        try {
            repository.updateSettings { it.copy(monitorEnabled = enabled) }
            repository.requestSave()
        } catch (t: Throwable) {
            AppLog.error("Service", "persist monitorEnabled failed", t)
        }
    }

    private fun flushSafely() {
        try {
            repository.flushBlocking()
        } catch (t: Throwable) {
            AppLog.error("Service", "flush failed", t)
        }
    }

    private fun stopSelfSafely() {
        try {
            stopSelf()
        } catch (t: Throwable) {
            AppLog.error("Service", "stopSelf failed", t)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户在最近任务里划掉 App：Manifest 声明 stopWithTask=false，服务继续运行；
        // 提醒与循环音不动（用户只是想收起界面），这里仅记录。
        AppLog.info("Service", "task removed, monitoring continues")
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val WAKE_LOCK_TAG = "LiveMonitor:check"
        /** 单房间检测的唤醒锁上限：超时自动释放，防止异常时长期持锁。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        /** 房间之间的固定间隔（桌面版单线程检测的 120ms 间隔）。 */
        private const val ROOM_GAP_MS = 120L
        /** 每房间在固定间隔之上再叠加的随机抖动上限，防止固定节奏打点。 */
        private const val ROOM_JITTER_MAX_MS = 2_000L
        private const val INITIAL_DELAY_MS = 1_200L
        /** 房间列表为空时的轻量等待：只重读设置，零网络。 */
        private const val EMPTY_ROOM_WAIT_MS = 2_000L
        private const val ERROR_RETRY_MS = 5_000L
        /** 平台开播时间与已记录时间相差超过该值时纠正（桌面版为 10 分钟）。 */
        private const val LIVE_START_CORRECTION_MS = 10 * 60 * 1000L
        private const val MAX_FAILURE_STREAK = 8

        /** 开播提醒发出后，自动打开直播间的延迟（与桌面版“3 秒后自动打开”一致）。 */
        private const val AUTO_OPEN_DELAY_MS = 3_000L
    }
}
