package com.livemonitor.app.core.notify

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.livemonitor.app.R
import com.livemonitor.app.util.AppLog
import java.io.File

/**
 * 提醒提示音播放器（内置 `res/raw/chime.wav` 或用户自定义音频）。
 *
 * 职责与分工：
 * - 开播提醒“首声”由本播放器播放（通知渠道 [NotificationHelper.CHANNEL_ALERTS] 已改为静音渠道，
 *   因此不会和系统通知音重叠）；
 * - 循环提示音也复用同一实例（[LoopSoundPlayer] 委托到本类），同一时间只有一个 MediaPlayer，
 *   上一声没播完时 [playOnce] 返回 false（不叠加）；
 * - 设置页试听走 [preview]。
 *
 * 守卫（安全/尊重用户设置）：
 * - [playOnce]：提示音开关关闭、系统静音/振动模式、免打扰、通知音量为 0、通知被用户关闭、
 *   提醒渠道被用户关闭（IMPORTANCE_NONE）时**跳过**；
 * - [preview]：用户主动试听，忽略“提示音开关/渠道开关”，但仍尊重系统静音/振动模式、免打扰、
 *   通知音量为 0（不在用户明确静音时突然出声）；
 * - 音频属性使用 USAGE_NOTIFICATION（受通知音量控制），请求瞬时焦点（可 duck 音乐），
 *   播放完成/停止/失败时立即释放焦点。
 *
 * 线程：建议在主线程调用（Service 通过 Handler.post、UI 本就在主线程）；方法本身加锁，
 * 多线程顺序调用安全。
 */
object AlertSoundPlayer {

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var loadedCustomFile: File? = null
    private var loadedCustomLength: Long = 0L
    private var loadedCustomModified: Long = 0L
    private var loadedBuiltIn = false
    private var focusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /**
     * 播放一次提醒音（首声或循环音）。
     * @param soundOn 用户的“开播时播放提示音”开关；false 时直接跳过。
     * @return 是否真正开始播放（false = 被守卫跳过/正在播放/解码失败）
     */
    fun playOnce(context: Context, customSoundFile: String, soundOn: Boolean): Boolean {
        synchronized(lock) {
            if (!soundOn) return false
            val app = context.applicationContext
            appContext = app
            if (!respectsSystemSound(app, checkAlertChannel = true)) return false
            return playInternal(app, customSoundFile)
        }
    }

    /**
     * 设置页试听：忽略“提示音开关/渠道开关”，但仍尊重系统静音/免打扰/通知音量为 0。
     * 若当前正在播放（例如循环音），先停止再重播，保证试听一定听得到。
     */
    fun preview(context: Context, customSoundFile: String): Boolean {
        synchronized(lock) {
            val app = context.applicationContext
            appContext = app
            if (!respectsSystemSound(app, checkAlertChannel = false)) return false
            stopInternal()
            return playInternal(app, customSoundFile)
        }
    }

    /** 立即停止当前播放（循环音停止/服务停止/暂停时调用）。 */
    fun stop() {
        synchronized(lock) {
            stopInternal()
        }
    }

    /** 释放 MediaPlayer 与音频焦点（服务销毁时调用；之后仍可再次 playOnce 自动重建）。 */
    fun release() {
        synchronized(lock) {
            stopInternal()
            releasePlayer()
            appContext = null
        }
    }

    /** 当前是否正在播放（诊断/测试用）。 */
    fun isPlaying(): Boolean = synchronized(lock) { player?.isPlaying == true }

    // ---------------- 内部实现 ----------------

    /** 系统级声音守卫：静音/振动模式、免打扰、通知音量 0，以及（可选）提醒渠道被关闭。 */
    private fun respectsSystemSound(context: Context, checkAlertChannel: Boolean): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager != null) {
            try {
                if (checkAlertChannel) {
                    if (!notificationManager.areNotificationsEnabled()) return false
                    val channel = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_ALERTS)
                    if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                        return false
                    }
                }
                if (notificationManager.currentInterruptionFilter !=
                    NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    return false
                }
            } catch (t: Throwable) {
                // 个别 ROM 的实现可能抛异常：不因为读取设置在播放路径上崩溃。
                AppLog.error("Notify", "sound state check failed", t)
            }
        }
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return true
        return try {
            when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL ->
                    audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0

                else -> false
            }
        } catch (t: Throwable) {
            AppLog.error("Notify", "ringer state check failed", t)
            true
        }
    }

    /** 调用方需持有 [lock]。 */
    private fun playInternal(context: Context, customSoundFile: String): Boolean {
        val customFile = CustomSoundStore.currentFile(context, customSoundFile)
        val mediaPlayer = ensurePlayer(context, customFile) ?: return false
        if (mediaPlayer.isPlaying) return false
        if (!requestAudioFocus(context)) return false
        return try {
            mediaPlayer.seekTo(0)
            mediaPlayer.start()
            true
        } catch (t: Throwable) {
            AppLog.error("Notify", "sound play failed", t)
            releasePlayer()
            abandonAudioFocus(context)
            false
        }
    }

    /** 挑选音源：自定义文件合法且存在则用自定义，否则回退内置 chime。 */
    private fun ensurePlayer(context: Context, customFile: File?): MediaPlayer? {
        val useCustom = customFile != null
        val current = player
        val customLength = if (useCustom) customFile!!.length() else 0L
        val customModified = if (useCustom) customFile!!.lastModified() else 0L
        if (current != null) {
            val sameSource = if (useCustom) {
                loadedCustomFile == customFile &&
                    loadedCustomLength == customLength &&
                    loadedCustomModified == customModified
            } else {
                loadedBuiltIn
            }
            if (sameSource) return current
        }
        releasePlayer()
        return try {
            val mediaPlayer = MediaPlayer()
            try {
                mediaPlayer.setAudioAttributes(audioAttributes)
                if (useCustom) {
                    // 只接受应用内部固定目录下的文件（CustomSoundStore 已做规范化校验）。
                    mediaPlayer.setDataSource(customFile!!.absolutePath)
                } else {
                    val afd = context.resources.openRawResourceFd(R.raw.chime)
                        ?: throw IllegalStateException("chime resource missing")
                    afd.use { mediaPlayer.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
                mediaPlayer.setOnCompletionListener { abandonAudioFocus(context) }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    AppLog.info("Notify", "sound media error what=$what extra=$extra")
                    releasePlayer()
                    abandonAudioFocus(context)
                    true
                }
                mediaPlayer.prepare()
            } catch (t: Throwable) {
                try {
                    mediaPlayer.release()
                } catch (_: Throwable) {
                    // 释放失败忽略
                }
                throw t
            }
            player = mediaPlayer
            loadedCustomFile = if (useCustom) customFile else null
            loadedCustomLength = customLength
            loadedCustomModified = customModified
            loadedBuiltIn = !useCustom
            mediaPlayer
        } catch (t: Throwable) {
            // 自定义文件损坏/格式不被设备支持时静默失败（不影响提醒通知本身）。
            AppLog.error("Notify", "sound prepare failed custom=$useCustom", t)
            loadedCustomFile = null
            loadedCustomLength = 0L
            loadedCustomModified = 0L
            loadedBuiltIn = false
            null
        }
    }

    private fun requestAudioFocus(context: Context): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return true
        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (t: Throwable) {
            AppLog.error("Notify", "audio focus request failed", t)
            false
        }
    }

    private fun abandonAudioFocus(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(request)
        } catch (_: Throwable) {
            // 释放失败忽略
        }
    }

    /** 调用方需持有 [lock]。 */
    private fun stopInternal() {
        val current = player ?: return
        try {
            if (current.isPlaying) current.pause()
            current.seekTo(0)
        } catch (t: Throwable) {
            AppLog.error("Notify", "sound stop failed", t)
        } finally {
            appContext?.let { abandonAudioFocus(it) }
        }
    }

    /** 调用方需持有 [lock]。 */
    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Throwable) {
            // 释放失败忽略
        }
        player = null
        loadedCustomFile = null
        loadedCustomLength = 0L
        loadedCustomModified = 0L
        loadedBuiltIn = false
    }
}
