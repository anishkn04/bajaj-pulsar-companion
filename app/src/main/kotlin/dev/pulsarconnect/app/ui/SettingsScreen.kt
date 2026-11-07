package dev.pulsarconnect.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pulsarconnect.app.ble.BleUiState

@Composable
internal fun SettingsScreen(
    bleState: BleUiState,
    onMirroringChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    onBootChange: (Boolean) -> Unit,
    onStopMirroring: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
) {
    val context = LocalContext.current
    val notificationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader(
            title = "Settings",
            subtitle = "Choose how the bike link behaves when you leave the app or reboot.",
        )

        SectionCard(title = "Bike link mirroring") {
            PulsarSwitchRow(
                title = "Mirror phone status to cluster",
                description = null,
                checked = bleState.settings.mirroring,
                onCheckedChange = onMirroringChange,
            )
            PulsarDivider()
            PulsarSwitchRow(
                title = "Keep mirroring in background",
                description = null,
                checked = bleState.settings.keepInBackground,
                onCheckedChange = onBackgroundChange,
            )
            PulsarDivider()
            PulsarSwitchRow(
                title = "Resume mirroring after reboot",
                description = null,
                checked = bleState.settings.startOnBoot,
                onCheckedChange = onBootChange,
            )
        }

        if (bleState.settings.keepInBackground && !notificationGranted) {
            AlertCard(
                title = "Notification permission is off",
                message = "The foreground service still runs, but you won't see its notification. " +
                    "Grant it to keep the stop control visible.",
                actionLabel = "Grant notification permission",
                onAction = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    }
                },
            )
        }

        if (bleState.settings.mirroring) {
            PillButton(
                label = "Stop mirroring now",
                onClick = onStopMirroring,
                style = PillStyle.Outlined,
            )
        }

        SectionCard(title = "Battery optimization") {
            Text(
                "If mirroring stops shortly after you leave the app, add Pulsar Connect to the " +
                    "battery-optimization exempt list so the foreground service is not killed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PillButton(
                label = "Open battery settings",
                onClick = onOpenBatteryOptimization,
            )
        }

        Text(
            "Mirroring, background mode and boot resume are global preferences. They are not " +
                "vehicle-specific and never store credentials, tokens or captures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
