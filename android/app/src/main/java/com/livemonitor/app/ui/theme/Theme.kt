package com.livemonitor.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 与桌面版一致的浅色、扁平、克制的视觉基调。
 *
 * 本应用固定浅色主题（与桌面版白底黑字一致），不跟随系统深色模式，
 * 避免未适配的深色配色出现对比度问题；状态色只用于状态圆点与卡片底色。
 */
val TextPrimary = Color(0xFF1F2D3D)
val TextSecondary = Color(0xFF8A94A6)
val BorderColor = Color(0xFFE3E8EF)
val PanelBg = Color(0xFFF7F9FC)

val StatusLive = Color(0xFF16A34A)
val StatusOffline = Color(0xFF9AA5B5)
val StatusWarn = Color(0xFFF59E0B)
val StatusBad = Color(0xFFEF4444)
val StatusChecking = Color(0xFF3B82F6)

val TintLive = Color(0xFFF0FDF4)
val TintWarn = Color(0xFFFFF7ED)
val TintBad = Color(0xFFFEF2F2)
val TintInfo = Color(0xFFEFF6FF)

val BorderLive = Color(0xFFBBF7D0)
val BorderWarn = Color(0xFFFDE68A)
val BorderBad = Color(0xFFFECACA)

private val LiveMonitorColors = lightColorScheme(
    primary = Color(0xFF16A34A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = TextPrimary,
    surface = Color.White,
    onSurface = TextPrimary,
    surfaceVariant = PanelBg,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = PanelBg,
    surfaceContainerHigh = Color(0xFFF2F5F9),
    surfaceContainerLow = Color(0xFFF9FAFC),
    outline = BorderColor,
    outlineVariant = Color(0xFFEDF1F5),
    error = StatusBad,
    onError = Color.White,
    errorContainer = TintBad,
    onErrorContainer = Color(0xFF7F1D1D),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LiveMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiveMonitorColors,
        shapes = AppShapes,
        content = content,
    )
}
