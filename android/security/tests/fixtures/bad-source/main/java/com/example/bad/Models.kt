package com.example.bad

/** SelfTest 夹具：自动打开默认开启（不安全）。 */
data class BadSettings(
    val autoOpenRoom: Boolean = true,
    val customSoundFile: String = "",
)
