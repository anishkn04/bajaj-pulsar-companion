package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pulsarconnect.app.ble.BleUiState

@Composable
internal fun HomeScreen(
    state: UiState.SignedIn,
    bleState: BleUiState,
    onSignOut: () -> Unit,
    onTrips: () -> Unit,
    onReminders: () -> Unit,
    onBike: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    val now = rememberCurrentTimeMillis()
    val pending = state.reminders.count { !it.completed }
    val due = reminderDueCount(state, now)
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

        HomeFeatureCard(
            title = "Bike link",
            value = bleStatusValue(bleState.connection),
            detail = bleStatusDetail(bleState),
            actionLabel = "Open bike link",
            onAction = onBike,
        )
        HomeFeatureCard(
            title = "Trips",
            value = "${state.trips.size}${if (state.hasMoreTrips) "+" else ""} saved trips",
            detail = if (state.activeTrip != null) {
                "A manual trip is in progress."
            } else {
                "Manually record start and end odometer readings. Local only; live cluster data is not decoded yet."
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
