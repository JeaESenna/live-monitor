package com.livemonitor.app.core.store

import android.content.Context
import android.content.SharedPreferences

/**
 * 键值存储抽象：生产环境用 SharedPreferences；单元测试用内存实现。
 * 这样仓储层（序列化/迁移/损坏恢复）可以在 JVM 上完整测试，无需模拟器。
 */
interface KvStore {
    fun getString(key: String): String?

    /** 一次性原子提交多个键；返回是否成功。 */
    fun putStrings(pairs: Map<String, String>): Boolean
}

class SharedPrefsKvStore(context: Context) : KvStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putStrings(pairs: Map<String, String>): Boolean {
        if (pairs.isEmpty()) return true
        val editor = prefs.edit()
        for ((key, value) in pairs) editor.putString(key, value)
        // commit() 同步落盘：配置/直播间列表不能因为进程被杀而静默丢失。
        return editor.commit()
    }

    companion object {
        private const val PREFS_NAME = "live_monitor_store"
    }
}

/** 仅供单元测试使用的内存实现。 */
class InMemoryKvStore(initial: Map<String, String> = emptyMap()) : KvStore {
    private val values = LinkedHashMap<String, String>(initial)

    override fun getString(key: String): String? = values[key]

    override fun putStrings(pairs: Map<String, String>): Boolean {
        values.putAll(pairs)
        return true
    }

    fun dump(): Map<String, String> = LinkedHashMap(values)
}
