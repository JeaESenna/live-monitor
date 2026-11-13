package com.livemonitor.app.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.livemonitor.app.AppGraph
import com.livemonitor.app.R
import com.livemonitor.app.core.jump.OpenResult
import com.livemonitor.app.core.jump.RoomJumpIntent
import com.livemonitor.app.core.jump.RoomLauncher
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.ui.theme.LiveMonitorTheme

/**
 * “进入直播间”的跳转/选择弹窗（透明 Activity，exported=false）。
 *
 * 安全约束：
 * - 只接收 [RoomJumpIntent] 携带的本机房间 key，不接收任何外部 URL；
 * - 房间信息一律从本地仓储重新查询，查不到直接结束，不显示错误细节；
 * - 绝不自动跳转外部：只有用户点击“打开App / 用浏览器打开网页版”才会通过
 *   [RoomLauncher] 发起外部跳转（目标固定为平台 App 或平台官方网页）；
 * - 未安装平台 App 时只提示用户自行在应用商店下载安装，**不提供任何下载/市场跳转入口**。
 */
class RoomJumpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题自身带 FLAG_DIM_BEHIND，而 Compose 弹窗还会再压暗一次；
        // 清掉 Activity 的 dim，避免“双重变暗”。
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val room = resolveRoomFromIntent()
        if (room == null) {
            // 房间不存在（可能已被删除）或 Intent 非法：静默结束。
            finish()
            return
        }

        when (RoomLauncher.openRoomInApp(this, room.platform, room.id, room.url)) {
            OpenResult.OpenedRoom -> finish()

            OpenResult.OpenedHome -> {
                toast(getString(R.string.ui_jump_opened_app))
                finish()
            }

            OpenResult.NotInstalled -> showChoiceDialog(room, appMissing = true)

            OpenResult.Failed -> showChoiceDialog(room, appMissing = false)
        }
    }

    private fun resolveRoomFromIntent(): RoomItem? {
        val roomKey = RoomJumpIntent.roomKey(intent)?.takeIf { it.isNotEmpty() } ?: return null
        return AppGraph.repository.snapshot().rooms.firstOrNull { it.key == roomKey }
    }

    private fun showChoiceDialog(room: RoomItem, appMissing: Boolean) {
        setContent {
            LiveMonitorTheme {
                RoomJumpDialog(
                    platformName = room.platform.displayName,
                    appMissing = appMissing,
                    onOpenAppHome = {
                        if (RoomLauncher.openAppHome(this, room.platform)) {
                            finish()
                        } else {
                            toast(getString(R.string.ui_jump_open_failed))
                        }
                    },
                    onOpenWeb = {
                        if (RoomLauncher.openWeb(this, room.url)) {
                            finish()
                        } else {
                            toast(getString(R.string.ui_jump_open_failed))
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Material3 选择弹窗：
 * - 未安装平台 App：只提示自行在应用商店下载安装，按钮为“用浏览器打开网页版/取消”，
 *   **没有下载或跳应用市场的入口**；
 * - 已安装但打不开直播间：按钮为“打开App / 用浏览器打开网页版 / 取消”。
 */
@Composable
private fun RoomJumpDialog(
    platformName: String,
    appMissing: Boolean,
    onOpenAppHome: () -> Unit,
    onOpenWeb: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_jump_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (appMissing) {
                            R.string.ui_jump_not_installed_message
                        } else {
                            R.string.ui_jump_failed_message
                        },
                        platformName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!appMissing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onOpenWeb,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ui_jump_open_web),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = if (appMissing) onOpenWeb else onOpenAppHome,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (appMissing) R.string.ui_jump_open_web else R.string.ui_jump_open_app,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.ui_cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}
