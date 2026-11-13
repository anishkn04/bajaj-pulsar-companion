package dev.pulsarconnect.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pulsarconnect.app.ble.BleViewModel
import dev.pulsarconnect.app.ui.AppViewModel
import dev.pulsarconnect.app.ui.PulsarConnectApp
import dev.pulsarconnect.app.ui.theme.PulsarTheme
import dev.pulsarconnect.app.ui.theme.ThemeSettingsStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val container = (application as PulsarConnectApplication).container
        val themeStore = ThemeSettingsStore(this)

        setContent {
            var themeSettings by remember { mutableStateOf(themeStore.load()) }
            PulsarTheme(themeSettings) {
                val viewModel: AppViewModel = viewModel(
                    factory = AppViewModel.factory(container),
                )
                val bleViewModel: BleViewModel = viewModel(
                    factory = container.bleViewModelFactory,
                )
                PulsarConnectApp(
                    viewModel = viewModel,
                    bleViewModel = bleViewModel,
                    themeSettings = themeSettings,
                    onThemeModeChange = { mode ->
                        themeSettings = themeSettings.copy(mode = mode).also(themeStore::save)
                    },
                    onDynamicColorChange = { enabled ->
                        themeSettings = themeSettings.copy(dynamicColor = enabled).also(themeStore::save)
                    },
                )
            }
        }
    }
}
