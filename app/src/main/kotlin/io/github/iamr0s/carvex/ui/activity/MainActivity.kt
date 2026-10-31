package io.github.iamr0s.carvex.ui.activity

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iamr0s.carvex.ui.model.DeviceViewModel
import io.github.iamr0s.carvex.ui.model.ThemeMode
import io.github.iamr0s.carvex.ui.screen.MainScreen
import io.github.iamr0s.carvex.ui.theme.DeviceAppTheme

class MainActivity : ComponentActivity() {
    private val vm: DeviceViewModel by viewModels()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.requestApplyInsets()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = when (state.settings.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Keep system bar icons readable when the in-app theme differs from the system theme.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            DeviceAppTheme(dark) { MainScreen(vm) }
        }
    }
}
