package com.livemonitor.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livemonitor.app.AppGraph
import com.livemonitor.app.ui.theme.LiveMonitorTheme

/**
 * 唯一导出的组件；没有深链接、没有外部 Intent 入口，也不接收任何 URL。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系统强制边到边：显式开启，页面自行用 Scaffold insets 规避系统栏遮挡。
        enableEdgeToEdge()
        setContent {
            LiveMonitorTheme {
                val mainViewModel: MainViewModel = viewModel()
                LiveMonitorApp(mainViewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入后台前同步落盘，避免进程被系统回收时丢掉最后一次修改。
        AppGraph.repository.flushBlocking()
    }
}

private enum class AppScreen { MAIN, SETTINGS }

private const val UI_PREFS_NAME = "ui_prefs_v1"
private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked_v1"

/**
 * 应用根：负责页面切换、Snackbar 宿主与 Android 13+ 通知权限的一次性引导。
 * 不持有 Activity 引用；权限结果由系统与设置页自行反映。
 */
@Composable
private fun LiveMonitorApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var screenName by rememberSaveable { mutableStateOf(AppScreen.MAIN.name) }

    // 首次进入时恢复监测（有房间且用户开启监测才启动前台服务）。
    LaunchedEffect(viewModel) {
        viewModel.ensureMonitoringStarted()
    }

    // 仓储/服务产生的一次性提示统一在这里展示。
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            val text = context.getString(message.textRes, *message.args.toTypedArray())
            snackbarHostState.showSnackbar(text)
        }
    }

    // Android 13+：首次进入（或首次添加房间）时请求一次通知权限；被拒绝后不再重复弹窗。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 权限状态由设置页读取系统状态展示，这里无需处理 */ }
    LaunchedEffect(snapshot.rooms.isNotEmpty()) {
        if (snapshot.rooms.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@LaunchedEffect
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect
        val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return@LaunchedEffect
        prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    when (screenName) {
        AppScreen.SETTINGS.name -> SettingsScreen(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBack = { screenName = AppScreen.MAIN.name },
        )

        else -> MainScreen(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onOpenSettings = { screenName = AppScreen.SETTINGS.name },
        )
    }
}
