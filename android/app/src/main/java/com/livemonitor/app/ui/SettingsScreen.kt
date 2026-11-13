package com.livemonitor.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livemonitor.app.R
import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.notify.CustomSoundStore
import com.livemonitor.app.ui.theme.PanelBg
import com.livemonitor.app.ui.theme.StatusBad
import com.livemonitor.app.ui.theme.StatusLive
import com.livemonitor.app.ui.theme.StatusWarn
import com.livemonitor.app.ui.theme.TextPrimary
import com.livemonitor.app.ui.theme.TextSecondary
import com.livemonitor.app.ui.theme.TintInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页：检测间隔、开播提醒、通知权限/电池优化引导、诊断日志、关于与隐私。
 * 所有设置项修改后立即写入本地仓储（由仓储异步落盘），无需“保存”按钮。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by viewModel.snapshot.collectAsState()
    val runtime by viewModel.runtime.collectAsState()
    val settings = snapshot.settings

    BackHandler(enabled = true, onBack = onBack)

    // 从系统设置页返回后重新读取权限/电池优化状态。
    val resumeStamp = rememberResumeStamp()
    var permissionTick by remember { mutableIntStateOf(0) }
    val notificationsEnabled = remember(resumeStamp, permissionTick) { areNotificationsEnabled(context) }
    val batteryIgnored = remember(resumeStamp) { isBatteryOptimizationIgnored(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    // 从系统文件选择器选择音频（SAF，无需存储权限；导入时复制到应用内部目录）。
    val soundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importCustomSound(uri)
    }

    var intervalDraft by remember(settings.intervalSec) { mutableFloatStateOf(settings.intervalSec.toFloat()) }
    var loopDraft by remember(settings.loopSoundSec) { mutableFloatStateOf(settings.loopSoundSec.toFloat()) }
    var autoCloseDraft by remember(settings.popupAutoCloseSec) {
        mutableFloatStateOf(settings.popupAutoCloseSec.toFloat())
    }

    var logDialogOpen by rememberSaveable { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(logDialogOpen) {
        if (logDialogOpen) logLines = viewModel.loadLogs()
    }

    val monitorStatusRes = when {
        runtime.running && runtime.paused -> R.string.ui_monitor_status_paused
        !settings.monitorEnabled -> R.string.ui_monitor_status_off
        runtime.running && runtime.checking -> R.string.ui_monitor_status_checking
        runtime.running -> R.string.ui_monitor_status_running
        else -> R.string.ui_monitor_status_stopped
    }

    val currentSoundLabel = if (settings.customSoundFile.isEmpty()) {
        stringResource(R.string.ui_sound_builtin)
    } else {
        stringResource(
            R.string.ui_sound_custom_format,
            CustomSoundStore.displayName(settings.customSoundFile),
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SettingsHeader(onBack = onBack)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { SectionTitle(R.string.ui_section_monitor) }
                item {
                    SettingsCard {
                        SwitchSettingRow(
                            title = stringResource(R.string.ui_setting_monitor_switch),
                            description = stringResource(
                                R.string.ui_setting_monitor_current,
                                stringResource(monitorStatusRes),
                            ),
                            checked = settings.monitorEnabled,
                            onCheckedChange = viewModel::setMonitorEnabled,
                        )
                        RowDivider()
                        SliderSettingRow(
                            title = stringResource(R.string.ui_setting_interval),
                            valueText = stringResource(
                                R.string.ui_setting_seconds,
                                intervalDraft.roundToInt(),
                            ),
                            value = intervalDraft,
                            onValueChange = { intervalDraft = it },
                            onValueChangeFinished = {
                                viewModel.setIntervalSec(
                                    intervalDraft.roundToInt().coerceIn(
                                        AppSettings.MIN_INTERVAL_SEC,
                                        AppSettings.MAX_INTERVAL_SEC,
                                    ),
                                )
                            },
                            valueRange = AppSettings.MIN_INTERVAL_SEC.toFloat()..300f,
                            hint = stringResource(R.string.ui_setting_interval_hint),
                            manualRange = AppSettings.MIN_INTERVAL_SEC..AppSettings.MAX_INTERVAL_SEC,
                            onManualCommit = { v ->
                                intervalDraft = v.toFloat()
                                viewModel.setIntervalSec(v)
                            },
                        )
                    }
                }

                item { SectionTitle(R.string.ui_section_alert) }
                item {
                    SettingsCard {
                        SwitchSettingRow(
                            title = stringResource(R.string.ui_setting_sound),
                            description = stringResource(R.string.ui_setting_sound_desc),
                            checked = settings.soundOn,
                            onCheckedChange = viewModel::setSoundOn,
                        )
                        RowDivider()
                        SliderSettingRow(
                            title = stringResource(R.string.ui_setting_loop),
                            valueText = if (loopDraft.roundToInt() == 0) {
                                stringResource(R.string.ui_setting_loop_off)
                            } else {
                                stringResource(R.string.ui_setting_seconds, loopDraft.roundToInt())
                            },
                            value = loopDraft,
                            onValueChange = { loopDraft = it },
                            onValueChangeFinished = {
                                viewModel.setLoopSoundSec(
                                    loopDraft.roundToInt().coerceIn(0, AppSettings.MAX_LOOP_SEC),
                                )
                            },
                            valueRange = 0f..300f,
                            hint = stringResource(R.string.ui_setting_loop_hint),
                            enabled = settings.soundOn,
                            manualRange = 0..AppSettings.MAX_LOOP_SEC,
                            onManualCommit = { v ->
                                loopDraft = v.toFloat()
                                viewModel.setLoopSoundSec(v)
                            },
                        )
                        RowDivider()
                        SliderSettingRow(
                            title = stringResource(R.string.ui_setting_autoclose),
                            valueText = if (autoCloseDraft.roundToInt() == 0) {
                                stringResource(R.string.ui_setting_autoclose_off)
                            } else {
                                stringResource(R.string.ui_setting_seconds, autoCloseDraft.roundToInt())
                            },
                            value = autoCloseDraft,
                            onValueChange = { autoCloseDraft = it },
                            onValueChangeFinished = {
                                viewModel.setPopupAutoCloseSec(
                                    autoCloseDraft.roundToInt()
                                        .coerceIn(0, AppSettings.MAX_AUTO_CLOSE_SEC),
                                )
                            },
                            valueRange = 0f..120f,
                            hint = stringResource(R.string.ui_setting_autoclose_hint),
                            manualRange = 0..AppSettings.MAX_AUTO_CLOSE_SEC,
                            onManualCommit = { v ->
                                autoCloseDraft = v.toFloat()
                                viewModel.setPopupAutoCloseSec(v)
                            },
                        )
                        RowDivider()
                        SwitchSettingRow(
                            title = stringResource(R.string.ui_setting_notify_live),
                            description = stringResource(R.string.ui_setting_notify_live_desc),
                            checked = settings.notifyAlreadyLive,
                            onCheckedChange = viewModel::setNotifyAlreadyLive,
                        )
                        RowDivider()
                        SwitchSettingRow(
                            title = stringResource(R.string.ui_setting_auto_open),
                            description = stringResource(R.string.ui_setting_auto_open_desc),
                            checked = settings.autoOpenRoom,
                            onCheckedChange = viewModel::setAutoOpenRoom,
                        )
                    }
                }

                item { SectionTitle(R.string.ui_section_sound) }
                item {
                    SettingsCard {
                        ActionSettingRow(
                            title = stringResource(R.string.ui_setting_current_sound),
                            description = currentSoundLabel,
                            actionText = stringResource(R.string.ui_sound_preview),
                            onAction = { viewModel.previewSound() },
                        )
                        RowDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { soundPickerLauncher.launch(arrayOf("audio/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.ui_sound_choose))
                            }
                            TextButton(
                                onClick = { viewModel.restoreDefaultSound() },
                                enabled = settings.customSoundFile.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.ui_sound_restore))
                            }
                        }
                        Text(
                            text = stringResource(R.string.ui_sound_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }

                item { SectionTitle(R.string.ui_section_permission) }
                item {
                    SettingsCard {
                        ActionSettingRow(
                            title = stringResource(R.string.ui_setting_notification),
                            description = stringResource(
                                if (notificationsEnabled) {
                                    R.string.ui_notification_enabled_summary
                                } else {
                                    R.string.ui_notification_disabled_summary
                                },
                            ),
                            descriptionColor = if (notificationsEnabled) StatusLive else StatusBad,
                            actionText = if (notificationsEnabled) {
                                null
                            } else {
                                stringResource(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        R.string.ui_notification_request
                                    } else {
                                        R.string.ui_notification_open_settings
                                    },
                                )
                            },
                            onAction = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scope.launch {
                                        if (!openNotificationSettings(context)) {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.ui_open_settings_failed),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                        if (!notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (!openNotificationSettings(context)) {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.ui_open_settings_failed),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .padding(start = 16.dp, bottom = 2.dp)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.ui_notification_open_settings))
                            }
                        }
                        RowDivider()
                        ActionSettingRow(
                            title = stringResource(R.string.ui_setting_battery),
                            description = stringResource(
                                if (batteryIgnored) R.string.ui_battery_ignored else R.string.ui_battery_not_ignored,
                            ),
                            descriptionColor = if (batteryIgnored) StatusLive else StatusWarn,
                            actionText = if (batteryIgnored) null else stringResource(R.string.ui_battery_open),
                            onAction = {
                                scope.launch {
                                    if (!openBatteryOptimizationSettings(context)) {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.ui_open_settings_failed),
                                        )
                                    }
                                }
                            },
                        )
                        Text(
                            text = stringResource(R.string.ui_battery_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }

                item { SectionTitle(R.string.ui_section_security) }
                item {
                    SettingsCard {
                        SwitchSettingRow(
                            title = stringResource(R.string.ui_setting_pinning),
                            description = stringResource(R.string.ui_setting_pinning_desc),
                            checked = settings.certificatePinning,
                            onCheckedChange = viewModel::setCertificatePinning,
                        )
                    }
                }

                item { SectionTitle(R.string.ui_section_log) }
                item {
                    SettingsCard {
                        ActionSettingRow(
                            title = stringResource(R.string.ui_setting_log),
                            description = stringResource(R.string.ui_log_entry_hint),
                            actionText = stringResource(R.string.ui_log_show),
                            onAction = { logDialogOpen = true },
                        )
                    }
                }

                item { SectionTitle(R.string.ui_section_about) }
                item {
                    SettingsCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ui_about_version),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                            )
                            AboutParagraph(R.string.ui_about_local)
                            AboutParagraph(R.string.ui_about_no_tracking)
                            AboutParagraph(R.string.ui_about_notify)
                            AboutParagraph(R.string.ui_about_check)
                            AboutParagraph(R.string.ui_about_no_open)
                            AboutParagraph(R.string.ui_about_compliance)
                        }
                    }
                }

                item { BatteryTipCard() }
            }
        }
    }

    if (logDialogOpen) {
        DiagnosticLogDialog(
            lines = logLines,
            onDismiss = { logDialogOpen = false },
            onClear = {
                viewModel.clearLogs()
                logLines = emptyList()
            },
        )
    }
}

@Composable
private fun AboutParagraph(@StringRes textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
}

/** 底部贴心提示：说明厂商省电策略的局限，并建议加入电池白名单。 */
@Composable
private fun BatteryTipCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = TintInfo),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = stringResource(R.string.ui_battery_tip_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ui_battery_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

/** 诊断日志弹窗：等宽小字、可滚动；只能复制到本机剪贴板或清空。 */
@Composable
private fun DiagnosticLogDialog(
    lines: List<String>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_log_title)) },
        text = {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            } else {
                Text(
                    text = lines.joinToString(separator = "\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.ui_close))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                context.getString(R.string.ui_log_title),
                                lines.joinToString(separator = "\n"),
                            ),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = lines.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.ui_log_copy))
                }
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.ui_log_clear))
                }
            }
        },
    )
}
