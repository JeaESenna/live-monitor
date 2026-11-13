package com.livemonitor.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.livemonitor.app.R
import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.platform.PlatformApi
import com.livemonitor.app.core.store.AddRoomResult
import com.livemonitor.app.ui.theme.StatusBad
import com.livemonitor.app.ui.theme.StatusLive
import com.livemonitor.app.ui.theme.TextPrimary
import com.livemonitor.app.ui.theme.TextSecondary

private const val MAX_INPUT_LEN = 512

/**
 * 添加直播间（底部弹窗）。
 *
 * 输入解析完全在本地完成（[PlatformApi.parseInput]），不发起网络请求；
 * 只有“添加”会写入本地仓储，之后由前台服务在后台检测。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRoomSheet(
    onDismiss: () -> Unit,
    onSubmit: (PlatformApi.ParsedInput, String) -> AddRoomResult,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var platformKey by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var remark by rememberSaveable { mutableStateOf("") }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    val platform = platformKey?.let { Platform.fromKey(it) }
    val trimmedInput = input.trim()
    val parsed = remember(input, platform) { PlatformApi.parseInput(input, platform) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_add_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ui_add_sheet_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ui_add_platform_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = platformKey == null,
                        onClick = {
                            platformKey = null
                            errorText = null
                        },
                        label = { Text(stringResource(R.string.ui_platform_auto)) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                    Platform.entries.forEach { item ->
                        FilterChip(
                            selected = platformKey == item.key,
                            onClick = {
                                platformKey = item.key
                                errorText = null
                            },
                            label = { Text(item.displayName) },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= MAX_INPUT_LEN) {
                            input = value
                            errorText = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_add_input_label)) },
                    placeholder = { Text(stringResource(R.string.ui_add_input_placeholder)) },
                    singleLine = true,
                    isError = errorText != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = {
                        if (input.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    input = ""
                                    errorText = null
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.ui_clear_input),
                                    tint = TextSecondary,
                                )
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
                ParsePreview(parsed = parsed, inputEmpty = trimmedInput.isEmpty())

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = remark,
                    onValueChange = { value ->
                        if (value.length <= RoomItem.MAX_REMARK_LEN) remark = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_add_remark_label)) },
                    placeholder = { Text(stringResource(R.string.ui_add_remark_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    supportingText = {
                        Text(
                            text = stringResource(
                                R.string.ui_add_remark_counter,
                                remark.length,
                                RoomItem.MAX_REMARK_LEN,
                            ),
                        )
                    },
                )

                if (errorText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.ui_cancel))
                    }
                    Button(
                        onClick = {
                            when (parsed) {
                                is PlatformApi.ParseOutcome.Ok -> {
                                    when (val result = onSubmit(parsed.input, remark)) {
                                        is AddRoomResult.Added -> onAdded()
                                        is AddRoomResult.Duplicate -> {
                                            errorText = context.getString(R.string.ui_add_error_duplicate)
                                        }

                                        is AddRoomResult.Invalid -> {
                                            errorText = context.getString(
                                                R.string.ui_add_error_invalid,
                                                result.reason,
                                            )
                                        }

                                        AddRoomResult.LimitReached -> {
                                            errorText = context.getString(
                                                R.string.ui_add_error_limit,
                                                AppSettings.MAX_ROOMS,
                                            )
                                        }
                                    }
                                }

                                is PlatformApi.ParseOutcome.Failure -> {
                                    errorText = parsed.message
                                }
                            }
                        },
                        enabled = trimmedInput.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.ui_add_confirm))
                    }
                }
            }
        }
    }
}

/** 本机解析预览：成功显示“平台 · 房间号”（不展示原始 URL），失败显示原因。 */
@Composable
private fun ParsePreview(parsed: PlatformApi.ParseOutcome, inputEmpty: Boolean) {
    when {
        inputEmpty -> PreviewLine(
            text = stringResource(R.string.ui_add_input_hint),
            color = TextSecondary,
            showCheck = false,
        )

        parsed is PlatformApi.ParseOutcome.Ok -> PreviewLine(
            text = stringResource(
                R.string.ui_add_preview_ok,
                parsed.input.platform.displayName,
                parsed.input.id,
            ),
            color = StatusLive,
            showCheck = true,
        )

        parsed is PlatformApi.ParseOutcome.Failure -> PreviewLine(
            text = parsed.message,
            color = StatusBad,
            showCheck = false,
        )
    }
}

@Composable
private fun PreviewLine(text: String, color: Color, showCheck: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (showCheck) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
