package io.github.iamr0s.carvex.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colors Material 3 has no slot for: the "connected" green and the log viewer.
 * Everything else comes from the Material color scheme (dynamic color on Android 12+).
 */
@Immutable
data class ExtraColors(
    val ok: Color,
    val okContainer: Color,
    val logBg: Color,
    val logHeader: Color,
    val logText: Color,
    val logDim: Color,
    val info: Color,
    val warn: Color,
    val bad: Color,
)

private val LightExtra = ExtraColors(
    ok = Color(0xFF1B6E3A), okContainer = Color(0xFFD2F0D8),
    logBg = Color(0xFF1C1B1F), logHeader = Color(0xFF2B2930),
    logText = Color(0xFFE6E1E5), logDim = Color(0xFF938F99),
    info = Color(0xFF8AB4F8), warn = Color(0xFFFFB74D), bad = Color(0xFFF2B8B5),
)

private val DarkExtra = LightExtra.copy(
    ok = Color(0xFF8FD6A0), okContainer = Color(0xFF1E4A2B),
)

private val LocalExtra = staticCompositionLocalOf { LightExtra }

object AppColors {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtra.current
}

// Claude-style typography: serif titles, default Material sans for everything else.
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 38.sp,
        ),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
    )
}

private val AppShapes = Shapes(
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DeviceAppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = when {
        // Material You: colors follow the user's wallpaper (Android 12+).
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Older devices: the standard Material 3 baseline scheme.
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
