package com.livemonitor.app.core.notify

import android.content.Context

/**
 * 循环提示音的调度外壳（真正的播放统一委托给 [AlertSoundPlayer]）。
 *
 * 设计原因：
 * - 首声由通知渠道改为“App 播放”后，首声与循环音必须共用同一个 MediaPlayer，
 *   才能从机制上保证“同一时间只有一声、不叠音”；
 * - 循环的节拍（Handler 调度、loopSoundSec+3s 首声保护间隔、提醒集合变化时停止）由
 *   [AlertNotifier] 负责，本类只做“用当前音源播一次”的薄封装；
 * - 音源（内置/自定义）与提示音开关由 [updateSource] 实时同步，设置变化无需重建播放器。
 */
class LoopSoundPlayer(private val context: Context) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var customSoundFile: String = ""

    @Volatile
    private var soundEnabled: Boolean = true

    /** 设置变化实时同步音源与开关。 */
    fun updateSource(customSoundFile: String, soundEnabled: Boolean) {
        this.customSoundFile = customSoundFile
        this.soundEnabled = soundEnabled
    }

    /**
     * 播放一次循环音；正在播放时返回 false（跳过本次，不叠加）。
     * 跟随“提示音开关/系统静音/勿扰/通知音量/渠道关闭”守卫（见 [AlertSoundPlayer.playOnce]）。
     */
    fun playChime(): Boolean =
        AlertSoundPlayer.playOnce(appContext, customSoundFile, soundEnabled)

    /** 停止循环音（立即停止当前播放）。 */
    fun stop() {
        AlertSoundPlayer.stop()
    }

    /** 服务销毁时释放共享播放器（UI 试听会在下次调用时自动重建）。 */
    fun release() {
        AlertSoundPlayer.release()
    }
}
