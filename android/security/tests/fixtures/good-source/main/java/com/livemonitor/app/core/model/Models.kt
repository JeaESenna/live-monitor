package com.livemonitor.app.core.model

/** SelfTest 夹具：设置默认值（自动打开默认关闭）。 */
data class AppSettings(
    val autoOpenRoom: Boolean = false,
    val customSoundFile: String = "",
) {
    companion object {
        const val MAX_CUSTOM_SOUND_BYTES = 2L * 1024 * 1024
    }
}
