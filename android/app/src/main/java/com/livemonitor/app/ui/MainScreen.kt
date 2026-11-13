package com.livemonitor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livemonitor.app.R
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.model.RoomStatusKeys
import com.livemonitor.app.ui.theme.BorderColor
import com.livemonitor.app.ui.theme.StatusChecking
import com.livemonitor.app.ui.theme.StatusLive
import com.livemonitor.app.ui.theme.StatusWarn
import com.livemonitor.app.ui.theme.TextPrimary
import com.livemonitor.app.ui.theme.TextSecondary
import com.livemonitor.app.util.TimeFormat
import kotlinx.coroutines.delay

/** 监测服务的界面阶段（由仓储设置 + 服务运行态推导，不额外持久化）。 */
private enum class MonitorPhase { OFF, STOPPED, PAUSED, CHECKING, RUNNING }

private fun monitorPhase(enabled: Boolean, running: Boolean, paused: Boolean, checking: Boolean): MonitorPhase = when {
    running && paused -> MonitorPhase.PAUSED
    !enabled -> MonitorPhase.OFF
    !running -> MonitorPhase.STOPPED
    checking -> MonitorPhase.CHECKING
    else -> MonitorPhase.RUNNING
}

/**
 * 主界面：顶栏 + 监测状态条 + 房间卡片列表 + 添加直播间 FAB。
 * 房间菜单里的“进入直播间”是唯一的外部跳转入口（用户主动点击才会打开，且由
 * RoomJumpActivity 二次校验后交给 RoomLauncher 执行）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsState()
    val runtime by viewModel.runtime.collectAsState()
    val settings = snapshot.settings
    val rooms = snapshot.rooms

    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var menuRoomKey by rememberSaveable { mutableStateOf("") }
    var deleteRoomKey by rememberSaveable { mutableStateOf("") }
    var manualRefreshing by remember { mutableStateOf(false) }

    // 下拉刷新的指示器只跟手动刷新绑定，避免每轮后台检测都闪一次。
    LaunchedEffect(manualRefreshing) {
        if (manualRefreshing) {
            delay(900)
            manualRefreshing = false
        }
    }

    val phase = monitorPhase(
        enabled = settings.monitorEnabled,
        running = runtime.running,
        paused = runtime.paused,
        checking = runtime.checking,
    )
    val liveCount = if (runtime.running && !runtime.paused) {
        runtime.liveCount
    } else {
        rooms.count { it.lastStatus == RoomStatusKeys.LIVE }
    }
    val lastCheckAt = runtime.lastCheckAt ?: rooms.mapNotNull { it.lastCheckAt }.maxOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.ui_add_room)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MainHeader(onOpenSettings = onOpenSettings)
            MonitorStatusCard(
                phase = phase,
                intervalSec = settings.intervalSec,
                roomCount = rooms.size,
                liveCount = liveCount,
                lastCheckAt = lastCheckAt,
                onPause = viewModel::pauseMonitoring,
                onResume = viewModel::resumeMonitoring,
            )
            PullToRefreshBox(
                isRefreshing = manualRefreshing,
                onRefresh = {
                    manualRefreshing = true
                    viewModel.checkNow()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (rooms.isEmpty()) {
                    EmptyState(onAdd = { showAddSheet = true })
                } else {
                    RoomList(
                        rooms = rooms,
                        menuRoomKey = menuRoomKey,
                        onOpenMenu = { key -> menuRoomKey = key },
                        onDismissMenu = { menuRoomKey = "" },
                        onCheckNow = {
                            menuRoomKey = ""
                            viewModel.checkNow()
                        },
                        onToggleNotify = { room ->
                            menuRoomKey = ""
                            viewModel.setRoomNotify(room.key, !room.notify)
                        },
                        onRequestDelete = { key ->
                            menuRoomKey = ""
                            deleteRoomKey = key
                        },
                        onEnterRoom = { room ->
                            menuRoomKey = ""
                            viewModel.openRoom(context, room.key)
                        },
                    )
                }
            }
        }
    }

    val pendingDelete = if (deleteRoomKey.isEmpty()) {
        null
    } else {
        rooms.firstOrNull { it.key == deleteRoomKey }
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteRoomKey = "" },
            title = { Text(stringResource(R.string.ui_delete_title)) },
            text = { Text(stringResource(R.string.ui_delete_message, pendingDelete.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeRoom(pendingDelete)
                        deleteRoomKey = ""
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteRoomKey = "" },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.ui_cancel))
                }
            },
        )
    }

    if (showAddSheet) {
        AddRoomSheet(
            onDismiss = { showAddSheet = false },
            onSubmit = { parsed, remark -> viewModel.addRoom(parsed, remark) },
            onAdded = { showAddSheet = false },
        )
    }
}

@Composable
private fun MainHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.ui_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.ui_settings),
                tint = TextPrimary,
            )
        }
    }
}

@Composable
private fun MonitorStatusCard(
    phase: MonitorPhase,
    intervalSec: Int,
    roomCount: Int,
    liveCount: Int,
    lastCheckAt: Long?,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val statusRes: Int
    val accent: Color
    val actionRes: Int
    val action: () -> Unit
    when (phase) {
        MonitorPhase.PAUSED -> {
            statusRes = R.string.ui_monitor_status_paused
            accent = StatusWarn
            actionRes = R.string.ui_monitor_action_resume
            action = onResume
        }

        MonitorPhase.OFF -> {
            statusRes = R.string.ui_monitor_status_off
            accent = TextSecondary
            actionRes = R.string.ui_monitor_action_enable
            action = onResume
        }

        MonitorPhase.STOPPED -> {
            statusRes = R.string.ui_monitor_status_stopped
            accent = TextSecondary
            actionRes = R.string.ui_monitor_action_start
            action = onResume
        }

        MonitorPhase.CHECKING -> {
            statusRes = R.string.ui_monitor_status_checking
            accent = StatusChecking
            actionRes = R.string.ui_monitor_action_pause
            action = onPause
        }

        MonitorPhase.RUNNING -> {
            statusRes = R.string.ui_monitor_status_running
            accent = StatusLive
            actionRes = R.string.ui_monitor_action_pause
            action = onPause
        }
    }

    val lastCheckText = lastCheckAt?.let {
        stringResource(R.string.ui_last_check_value, TimeFormat.lastCheck(it))
    } ?: stringResource(R.string.ui_last_check_none)
    val detail = stringResource(
        R.string.ui_status_detail,
        intervalSec,
        roomCount,
        liveCount,
        lastCheckText,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = action,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(actionRes),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun RoomList(
    rooms: List<RoomItem>,
    menuRoomKey: String,
    onOpenMenu: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onCheckNow: () -> Unit,
    onToggleNotify: (RoomItem) -> Unit,
    onRequestDelete: (String) -> Unit,
    onEnterRoom: (RoomItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rooms, key = { it.key }) { room ->
            RoomCard(
                room = room,
                menuExpanded = menuRoomKey == room.key,
                onOpenMenu = { onOpenMenu(room.key) },
                onDismissMenu = onDismissMenu,
                onCheckNow = onCheckNow,
                onToggleNotify = { onToggleNotify(room) },
                onRequestDelete = { onRequestDelete(room.key) },
                onEnterRoom = { onEnterRoom(room) },
            )
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = TextSecondary,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.ui_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ui_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ui_empty_action))
            }
        }
    }
}
