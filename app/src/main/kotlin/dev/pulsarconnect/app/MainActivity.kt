package dev.pulsarconnect.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pulsarconnect.app.ble.BleViewModel
import dev.pulsarconnect.app.ui.AppViewModel
import dev.pulsarconnect.app.ui.PulsarConnectApp
import dev.pulsarconnect.app.ui.theme.PulsarTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val container = (application as PulsarConnectApplication).container

        setContent {
            PulsarTheme {
                val viewModel: AppViewModel = viewModel(
                    factory = AppViewModel.factory(container),
                )
                val bleViewModel: BleViewModel = viewModel(
                    factory = container.bleViewModelFactory,
                )
                PulsarConnectApp(viewModel, bleViewModel)
            }
        }
    }
}
