package com.lyrnox.edgessh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 浅色配色：主色清爽蓝 #007AFF，背景 #F1F5FB。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF5AC8FA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F2FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = Color(0xFFFF9EC6),
    background = Color(0xFFF1F5FB),
    onBackground = Color(0xFF16181D),
    surface = Color.White,
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE2E9F3),
    onSurfaceVariant = Color(0xFF454A54),
    outline = Color(0xFFC3CAD6),
    error = Color(0xFFFF3B30),
)

/** 深色配色：与浅色同色系，压暗背景。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00376B),
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFF5AC8FA),
    onSecondary = Color(0xFF001D33),
    secondaryContainer = Color(0xFF004A6B),
    onSecondaryContainer = Color(0xFFD8F2FF),
    tertiary = Color(0xFFFF9EC6),
    background = Color(0xFF0C0F14),
    onBackground = Color(0xFFE6EAF1),
    surface = Color(0xFF131720),
    onSurface = Color(0xFFE6EAF1),
    surfaceVariant = Color(0xFF1D232E),
    onSurfaceVariant = Color(0xFFB9C1CE),
    outline = Color(0xFF3A4250),
    error = Color(0xFFFF6961),
)

/** iOS 风格大圆角。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 应用主题入口。 */
@Composable
fun EdgeSshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
