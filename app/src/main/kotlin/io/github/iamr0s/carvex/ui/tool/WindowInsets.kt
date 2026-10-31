package io.github.iamr0s.carvex.ui.tool

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues as composeAsPaddingValues
import androidx.compose.foundation.layout.exclude as composeExclude
import androidx.compose.foundation.layout.only as composeOnly
import androidx.compose.foundation.layout.union as composeUnion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * Insets shared by the whole UI. [content] is the inset that belongs to the current
 * layout branch; for the phone home page it excludes the bottom bar inset because
 * Scaffold already reserves that space.
 */
data class AppInsets(
    val safeDrawing: WindowInsets,
    val content: WindowInsets,
    /** Insets consumed by the navigation rail on the start edge. */
    val dock: WindowInsets = content,
    /** Insets for the middle device/settings pane. */
    val middle: WindowInsets = content,
    /** Insets for the trailing detail/log pane. */
    val detail: WindowInsets = content,
)

val LocalAppInsets = staticCompositionLocalOf<AppInsets> {
    error("AppInsets must be provided by MainScreen")
}

@Composable
fun ProvideAppInsets(
    insets: AppInsets,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppInsets provides insets, content = content)
}

/** Keep the same dynamic inset while dropping selected edges. */
fun WindowInsets.exclude(sides: WindowInsetsSides): WindowInsets =
    composeExclude(composeOnly(sides))

/** Add another dynamic inset without freezing its current pixel values. */
fun WindowInsets.include(insets: WindowInsets): WindowInsets =
    composeUnion(insets)

/** Keep only the requested edges. */
fun WindowInsets.onlySides(sides: WindowInsetsSides): WindowInsets =
    composeOnly(sides)

/** Density aware conversion; call from composition so rotation and density changes update it. */
@Composable
fun WindowInsets.toPaddingValues(): PaddingValues = composeAsPaddingValues()

/** Density and RTL aware reverse conversion for utility code that receives PaddingValues. */
@Composable
fun PaddingValues.toWindowInsets(): WindowInsets {
    val direction = LocalLayoutDirection.current
    return WindowInsets(
        left = calculateLeftPadding(direction),
        top = calculateTopPadding(),
        right = calculateRightPadding(direction),
        bottom = calculateBottomPadding(),
    )
}
