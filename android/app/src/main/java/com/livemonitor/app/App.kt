package com.livemonitor.app

import android.app.Application
import android.content.Context
import com.livemonitor.app.core.notify.NotificationHelper
import com.livemonitor.app.core.platform.PlatformHttp
import com.livemonitor.app.core.store.AppRepository
import com.livemonitor.app.core.store.SharedPrefsKvStore
import com.livemonitor.app.util.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // 严格证书固定默认开启；以用户设置为准（关闭后检测器换用未固定客户端）。
        PlatformHttp.pinningEnabled = AppGraph.repository.state.value.settings.certificatePinning
        NotificationHelper.createChannels(this)
        AppLog.info("App", "process started")
    }
}

/**
 * 极简的依赖入口（这个应用只有一个仓储和一个服务，不引入 DI 框架以减少攻击面）。
 * 仓储是进程内单例：Activity 与前台服务共享同一份状态。
 */
object AppGraph {
    @Volatile
    private var repositoryRef: AppRepository? = null

    fun init(context: Context) {
        if (repositoryRef != null) return
        synchronized(this) {
            if (repositoryRef != null) return
            AppLog.init(context.applicationContext)
            repositoryRef = AppRepository(SharedPrefsKvStore(context.applicationContext))
        }
    }

    val repository: AppRepository
        get() = repositoryRef ?: error("AppGraph 尚未初始化")
}
