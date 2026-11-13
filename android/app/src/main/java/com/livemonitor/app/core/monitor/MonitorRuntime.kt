package com.livemonitor.app.core.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 前台监测服务的运行状态快照（只存在内存中，不持久化）。
 * UI 通过 [MonitorRuntimeHolder.runtime] 订阅，服务负责更新。
 */
data class MonitorRuntime(
    val running: Boolean = false,
    val paused: Boolean = false,
    val checking: Boolean = false,
    val lastCheckAt: Long? = null,
    val liveCount: Int = 0,
    val lastError: String = "",
    val startedAt: Long? = null,
) {
    fun normalize(): MonitorRuntime = if (!running) {
        MonitorRuntime()
    } else {
        copy(lastError = lastError.take(80))
    }
}

object MonitorRuntimeHolder {
    private val _runtime = MutableStateFlow(MonitorRuntime())
    val runtime: StateFlow<MonitorRuntime> = _runtime.asStateFlow()

    fun update(transform: (MonitorRuntime) -> MonitorRuntime) {
        _runtime.update { transform(it).normalize() }
    }

    fun reset() {
        _runtime.value = MonitorRuntime()
    }
}
