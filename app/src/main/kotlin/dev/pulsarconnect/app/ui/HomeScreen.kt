package dev.pulsarconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pulsarconnect.app.ble.BleUiState

@Composable
internal fun HomeScreen(
    state: UiState.SignedIn,
    bleState: BleUiState,
    onSignOut: () -> Unit,
    onTrips: () -> Unit,
    onReminders: () -> Unit,
    onBike: () -> Unit,
    onSettings: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val now = rememberCurrentTimeMillis()
    val pending = state.reminders.count { !it.completed }
    val due = reminderDueCount(state, now)
    val health = rememberSetupHealth(bleState)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(
            eyebrow = "Pulsar Connect",
            title = state.bikeName ?: "Pulsar",
        )
        Text(
            text = state.vin,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LocalDataStatus(state, onClearError, onRetry)

        SetupHealthCard(health) { action ->
            when (action) {
                SetupHealthAction.BIKE_PERMISSIONS,
                SetupHealthAction.SELECTED_BIKE -> onBike()

                SetupHealthAction.BACKGROUND_MODE,
                SetupHealthAction.BOOT_RESUME -> onSettings()

                SetupHealthAction.NOTIFICATION_ACCESS -> openNotificationAccessSettings(context)
                SetupHealthAction.NOTIFICATION_PERMISSION -> openAppNotificationSettings(context)
                SetupHealthAction.BATTERY_OPTIMIZATION -> openBatteryOptimizationSettings(context)
            }
        }

        RecordSummaryCard(state, now)

        HomeFeatureCard(
            title = "Bike link",
            value = bleStatusValue(bleState.connection),
            detail = bleStatusDetail(bleState),
            actionLabel = "Open bike link",
            onAction = onBike,
        )
        HomeFeatureCard(
            title = "Trips",
            value = "${state.trips.count { it.endTime != null }}${if (state.hasMoreTrips) "+" else ""} saved trips",
            detail = if (state.activeTrip != null) {
                "A manual trip is in progress."
            } else {
                "Manually record start and end odometer readings."
            },
            actionLabel = "View trips",
            onAction = onTrips,
        )
        HomeFeatureCard(
            title = "Reminders",
            value = "$pending pending · $due due",
            detail = "Due status uses dates and your latest recorded odometer, not live bike data.",
            actionLabel = "Manage reminders",
            onAction = onReminders,
        )

        PulsarDivider()
        Text(
            "Signing out stops reminder alerts but keeps your alert preference and local trip and reminder data on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSignOut, enabled = !state.busy) {
                Text(
                    "Sign out",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordSummaryCard(state: UiState.SignedIn, now: Long) {
    val next = nextPendingReminder(state.reminders, state.lastOdometer, now)
    PulsarCard {
        Text(
            text = "Record summary",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricBlock(
                label = "Total distance",
                value = "${formatRecordNumber(state.totalDistanceKm)} km",
                modifier = Modifier.weight(1f),
            )
            MetricBlock(
                label = "Trips this month",
                value = state.tripsThisMonth.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        PulsarDivider()
        Text(
            text = "Next due reminder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = next?.title ?: "Nothing pending",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = next?.let { reminderTimingLabel(it, now) } ?: "No pending service reminders.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberSetupHealth(bleState: BleUiState): SetupHealth {
    val context = LocalContext.current
    var resumeVersion by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationsGranted = remember(context, resumeVersion) {
        notificationPermissionGranted(context)
    }
    val batteryIgnored = remember(context, resumeVersion) {
        batteryOptimizationIgnored(context)
    }
    return remember(bleState, notificationsGranted, batteryIgnored) {
        setupHealth(bleState, notificationsGranted, batteryIgnored)
    }
}

@Composable
private fun SetupHealthCard(
    health: SetupHealth,
    onAction: (SetupHealthAction) -> Unit,
) {
    PulsarCard {
        Text(
            text = "Setup health",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = health.summary,
            style = MaterialTheme.typography.titleLarge,
            color = if (health.ready) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        health.items.forEachIndexed { index, item ->
            if (index > 0) PulsarDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(item.action) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (item.needsAttention) {
                            Icons.Outlined.ErrorOutline
                        } else {
                            Icons.Outlined.CheckCircle
                        },
                        contentDescription = null,
                        tint = if (item.needsAttention) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = item.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.needsAttention) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (item.needsAttention) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp),
                    )
                }
            }
        }
        if (!health.ready) {
            Text(
                text = "Tap an item to open the setting that fixes it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeFeatureCard(
    title: String,
    value: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    PulsarCard {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardLink(label = actionLabel, onClick = onAction)
    }
}
