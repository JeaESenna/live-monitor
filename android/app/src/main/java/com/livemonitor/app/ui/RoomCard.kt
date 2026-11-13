package com.livemonitor.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livemonitor.app.R
import com.livemonitor.app.core.model.RoomItem
import com.livemonitor.app.core.model.RoomStatusKeys
import com.livemonitor.app.ui.theme.BorderBad
import com.livemonitor.app.ui.theme.BorderColor
import com.livemonitor.app.ui.theme.BorderLive
import com.livemonitor.app.ui.theme.BorderWarn
import com.livemonitor.app.ui.theme.StatusBad
import com.livemonitor.app.ui.theme.StatusChecking
import com.livemonitor.app.ui.theme.StatusLive
import com.livemonitor.app.ui.theme.StatusOffline
import com.livemonitor.app.ui.theme.StatusWarn
import com.livemonitor.app.ui.theme.TextPrimary
import com.livemonitor.app.ui.theme.TextSecondary
import com.livemonitor.app.ui.theme.TintBad
import com.livemonitor.app.ui.theme.TintLive
import com.livemonitor.app.ui.theme.TintWarn
import com.livemonitor.app.util.TimeFormat

/** 房间状态对应的文案与配色（绿/灰/橙/红）。 */
private data class RoomStatusInfo(
    @StringRes val labelRes: Int,
    val dotColor: Color,
    val containerColor: Color,
    val borderColor: Color,
    val highlighted: Boolean = false,
)

private fun resolveRoomStatus(room: RoomItem): RoomStatusInfo {
    val transient = room.lastTransient || room.lastSub.contains("网络异常")
    return when {
        room.lastStatus == RoomStatusKeys.LIVE && transient ->
            RoomStatusInfo(R.string.ui_status_live_transient, StatusWarn, TintWarn, BorderWarn)

        room.lastStatus == RoomStatusKeys.LIVE && room.lastSub.contains("轮播") ->
            RoomStatusInfo(R.string.ui_status_replay, StatusLive, TintLive, BorderLive, highlighted = true)

        room.lastStatus == RoomStatusKeys.LIVE ->
            RoomStatusInfo(R.string.ui_status_live, StatusLive, TintLive, BorderLive, highlighted = true)

        room.lastStatus == RoomStatusKeys.OFFLINE && transient ->
            RoomStatusInfo(R.string.ui_status_offline_transient, StatusWarn, TintWarn, BorderWarn)

        room.lastStatus == RoomStatusKeys.OFFLINE ->
            RoomStatusInfo(R.string.ui_status_offline, StatusOffline, Color.White, BorderColor)

        room.lastStatus == RoomStatusKeys.NOT_FOUND ->
            RoomStatusInfo(R.string.ui_status_notfound, StatusBad, TintBad, BorderBad)

        room.lastStatus == RoomStatusKeys.ERROR ->
            RoomStatusInfo(R.string.ui_status_error, StatusBad, TintBad, BorderBad)

        else ->
            RoomStatusInfo(R.string.ui_status_checking, StatusChecking, Color.White, BorderColor)
    }
}

/**
 * 单个直播间卡片：状态、主播、平台/分区、直播标题、最后检测/上次开播与提醒开关标签。
 * 点击或长按打开操作菜单（立即检测 / 开/关提醒 / 删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoomCard(
    room: RoomItem,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onCheckNow: () -> Unit,
    onToggleNotify: () -> Unit,
    onRequestDelete: () -> Unit,
    onEnterRoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = remember(room.lastStatus, room.lastTransient, room.lastSub) { resolveRoomStatus(room) }
    val statusLabel = stringResource(status.labelRes)
    val areaText = room.area.ifBlank { stringResource(R.string.ui_value_empty) }
    val lastCheckText = stringResource(R.string.ui_last_check, TimeFormat.lastCheck(room.lastCheckAt))
    val lastLiveText = stringResource(R.string.ui_last_live, TimeFormat.lastLive(room.lastLiveAt))
    val moreLabel = stringResource(R.string.ui_room_more)

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = moreLabel,
                    onLongClickLabel = moreLabel,
                    onClick = onOpenMenu,
                    onLongClick = onOpenMenu,
                ),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = status.containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, status.borderColor),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.clearAndSetSemantics { contentDescription = statusLabel },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(status.dotColor),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = status.dotColor,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    NotifyTag(enabled = room.notify)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = room.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ui_platform_area, room.platform.displayName, areaText),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = room.lastTitle.ifBlank { stringResource(R.string.ui_value_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.highlighted) TextPrimary else TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ui_room_meta, lastCheckText, lastLiveText),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_room_menu_enter)) },
                onClick = onEnterRoom,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_room_menu_check)) },
                onClick = onCheckNow,
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (room.notify) R.string.ui_room_menu_notify_off else R.string.ui_room_menu_notify_on,
                        ),
                    )
                },
                onClick = onToggleNotify,
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.ui_room_menu_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onRequestDelete,
            )
        }
    }
}

/** 提醒开关标签（只读展示；开关操作在卡片菜单里）。 */
@Composable
private fun NotifyTag(enabled: Boolean) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(if (enabled) R.string.ui_notify_on else R.string.ui_notify_off),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
