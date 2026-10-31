package io.github.iamr0s.carvex.ui.screen

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iamr0s.carvex.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iamr0s.carvex.ui.component.MethodChooser
import io.github.iamr0s.carvex.ui.model.DeviceViewModel
import io.github.iamr0s.carvex.ui.model.RealDeviceTransport
import io.github.iamr0s.carvex.ui.model.Link
import io.github.iamr0s.carvex.ui.model.UiState
import io.github.iamr0s.carvex.ui.page.DeviceInfoPage
import io.github.iamr0s.carvex.ui.page.DevicesPage
import io.github.iamr0s.carvex.ui.page.LogPage
import io.github.iamr0s.carvex.ui.page.SettingsPage
import io.github.iamr0s.carvex.ui.tool.AppInsets
import io.github.iamr0s.carvex.ui.tool.ProvideAppInsets
import io.github.iamr0s.carvex.ui.tool.exclude
import io.github.iamr0s.carvex.ui.tool.onlySides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Every screen the app can show. Manage and Logs are "detail" pages pushed on top of a tab. */
enum class Dest {
    Devices, Settings, Manage, Logs;

    val isDetail: Boolean get() = this == Manage || this == Logs

    /** Which tab (rail / bottom bar item) this destination belongs to. */
    val tab: Dest get() = if (this == Dest.Settings || this == Dest.Logs) Dest.Settings else Dest.Devices
}

/** Navigation callbacks handed to the screens. */
class Nav(
    val openManage: () -> Unit,
    val openLogs: () -> Unit,
    val back: () -> Unit,
    val askMethod: (deviceId: Int, change: Boolean) -> Unit,
)

private data class Chooser(val deviceId: Int, val change: Boolean)

/**
 * Root of the UI.
 *  - Phone (< 720 dp wide): bottom bar, one page at a time, Manage and Logs slide in on top.
 *  - Tablet (>= 720 dp): navigation rail, two panes. Devices | Manage and Settings | Logs.
 */
@Composable
fun MainScreen(vm: DeviceViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    var dest by rememberSaveable { mutableStateOf(Dest.Devices) }
    var chooser by remember { mutableStateOf<Chooser?>(null) }
    val backProgress = remember { Animatable(0f) }
    val backSettleScope = rememberCoroutineScope()
    var suppressNextPopAnimation by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val saveableStateHolder = rememberSaveableStateHolder()
    val safeDrawing = WindowInsets.safeDrawing
    val contentInsets = if (!wide && !dest.isDetail) {
        safeDrawing.exclude(WindowInsetsSides.Bottom)
    } else {
        safeDrawing
    }
    val appInsets = AppInsets(
        safeDrawing = safeDrawing,
        content = contentInsets,
        dock = safeDrawing.exclude(WindowInsetsSides.End),
        middle = safeDrawing.exclude(WindowInsetsSides.Horizontal),
        detail = safeDrawing.exclude(WindowInsetsSides.Start),
    )

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    // "Keep screen on" only while a device is connected.
    val view = LocalView.current
    val keepOn = state.settings.keepScreenOn && state.link is Link.Connected
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // On a phone, leave the Manage page when the device is gone.
    LaunchedEffect(state.link, wide) {
        if (!wide && dest == Dest.Manage && state.link is Link.Idle) dest = Dest.Devices
    }

    val goBack = { dest = if (dest == Dest.Manage) Dest.Devices else Dest.Settings }
    LaunchedEffect(dest) {
        if (suppressNextPopAnimation) {
            withFrameNanos { }
            suppressNextPopAnimation = false
        }
    }
    // Activity 1.13 dispatches this callback for both the edge swipe and the
    // ordinary back button. Collecting the progress keeps the detail page
    // moving with the user's finger; cancellation restores it in place.
    PredictiveBackHandler(enabled = !wide && dest.isDetail) { progress ->
        var committed = false
        try {
            progress.collect { event -> backProgress.snapTo(event.progress) }
            committed = true
            // The system predictive-back animation has already moved the page with the finger.
            // The destination change must be an immediate hand-off, otherwise AnimatedContent
            // starts a second pop animation from the untouched origin.
            suppressNextPopAnimation = true
            goBack()
            backProgress.snapTo(0f)
        } catch (_: CancellationException) {
            if (!committed) {
                backSettleScope.launch {
                    backProgress.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val nav = remember {
        Nav(
            openManage = { dest = Dest.Manage },
            openLogs = { dest = Dest.Logs },
            back = { goBack() },
            askMethod = { id, change -> chooser = Chooser(id, change) },
        )
    }

    ProvideAppInsets(appInsets) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide && !dest.isDetail) {
                    NavigationBar(
                        windowInsets = appInsets.safeDrawing.onlySides(WindowInsetsSides.Bottom),
                    ) {
                        NavigationBarItem(
                            selected = dest.tab == Dest.Devices,
                            onClick = { dest = Dest.Devices },
                            icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_devices)) },
                        )
                        NavigationBarItem(
                            selected = dest.tab == Dest.Settings,
                            onClick = { dest = Dest.Settings },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_settings)) },
                        )
                    }
                }
            },
        ) { pad ->
        AnimatedContent(
            targetState = wide,
            modifier = Modifier.padding(pad).fillMaxSize(),
            transitionSpec = {
                (
                    fadeIn(tween(260)) +
                        scaleIn(tween(260), initialScale = 0.98f)
                    ) togetherWith (
                    fadeOut(tween(180)) +
                        scaleOut(tween(180), targetScale = 1.02f)
                    ) using SizeTransform(clip = false)
            },
            label = "window-size-layout",
        ) { layoutWide ->
        if (layoutWide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    windowInsets = appInsets.dock,
                ) {
                    Spacer(Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = dest.tab == Dest.Devices,
                        onClick = { dest = Dest.Devices },
                        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_devices)) },
                    )
                    NavigationRailItem(
                        selected = dest.tab == Dest.Settings,
                        onClick = { dest = Dest.Settings },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                    )
                }
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    ProvideAppInsets(appInsets.copy(content = appInsets.middle)) {
                        if (dest.tab == Dest.Devices) DevicesPage(state, vm, nav)
                        else SettingsPage(state, vm, nav)
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = dest.takeIf { it == Dest.Manage || it == Dest.Logs },
                        transitionSpec = {
                            when {
                                initialState == null && targetState != null ->
                                    (slideInHorizontally(tween(360)) { it / 2 } + fadeIn(tween(360))) togetherWith
                                        fadeOut(tween(160))
                                initialState != null && targetState == null ->
                                    fadeIn(tween(180)) togetherWith
                                        (slideOutHorizontally(tween(280)) { it / 2 } + fadeOut(tween(240)))
                                else -> fadeIn(tween(240)) togetherWith fadeOut(tween(160))
                            }
                        },
                        label = "tablet-detail-pane",
                    ) { pane ->
                        if (pane == null) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            ProvideAppInsets(appInsets.copy(content = appInsets.detail)) {
                                when (pane) {
                                    Dest.Manage -> DeviceInfoPage(state, vm, nav, showBack = false)
                                    Dest.Logs -> LogPage(state, vm, nav, showBack = false)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = dest,
                modifier = Modifier.graphicsLayer {
                        translationX = size.width * backProgress.value * 0.18f
                        scaleX = 1f - backProgress.value * 0.02f
                        scaleY = 1f - backProgress.value * 0.02f
                        alpha = 1f - backProgress.value * 0.08f
                    },
                label = "screen",
                transitionSpec = {
                    val push = targetState.isDetail && !initialState.isDetail
                    val pop = !targetState.isDetail && initialState.isDetail
                    when {
                        pop && suppressNextPopAnimation ->
                            fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                        push -> (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } +
                            fadeIn(tween(300))) togetherWith fadeOut(tween(150))
                        pop -> fadeIn(tween(250)) togetherWith
                            (slideOutHorizontally(tween(250)) { it / 4 } + fadeOut(tween(200)))
                        else -> fadeIn(tween(250)) togetherWith fadeOut(tween(150))
                    }
                },
            ) { d ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    saveableStateHolder.SaveableStateProvider(d.name) {
                        Page(d, state, vm, nav)
                    }
                }
            }
        }
        }
        }

        // Connect / change-method chooser: bottom sheet on phones, dialog on tablets.
        chooser?.let { c ->
            val device = state.device(c.deviceId)
            if (device != null) {
                MethodChooser(
                    deviceName = device.name,
                    change = c.change,
                    allowed = state.settings.methods.intersect(RealDeviceTransport.supportedMethods),
                    wide = wide,
                    onPick = { method -> chooser = null; vm.connect(c.deviceId, method) },
                    onDismiss = { chooser = null },
                )
            }
        }
    }
}

@Composable
private fun Page(d: Dest, state: UiState, vm: DeviceViewModel, nav: Nav) {
    when (d) {
        Dest.Devices -> DevicesPage(state, vm, nav)
        Dest.Settings -> SettingsPage(state, vm, nav)
        Dest.Manage -> DeviceInfoPage(state, vm, nav, showBack = true)
        Dest.Logs -> LogPage(state, vm, nav, showBack = true)
    }
}

