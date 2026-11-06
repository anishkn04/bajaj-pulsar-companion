package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pulsarconnect.core.model.Trip
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun TripHistoryScreen(
    state: UiState.SignedIn,
    onStartTrip: (String) -> Unit,
    onEndTrip: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = state.activeTrip
    var odometer by rememberSaveable(state.vin, active?.id, active?.startTime) {
        mutableStateOf("")
    }
    val enabled = !state.busy && !state.localLoading && !state.localLoadFailed
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Ride log",
                title = "Trips",
                subtitle = "Manual, local-only records. Enter the odometer from your bike; trips are " +
                    "not recorded over Bluetooth or synced to the cloud.",
            )
        }
        item { LocalDataStatus(state, onClearError, onRetry) }

        item {
            PulsarCard {
                Text(
                    text = if (active == null) "Start a manual trip" else "Trip in progress",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (active != null) {
                    Text(
                        "Started ${formatRecordDateTime(active.startTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Start odometer: ${formatRecordNumber(active.startOdometer)} km",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text(if (active == null) "Start odometer (km)" else "End odometer (km)") },
                    placeholder = { Text("1234.5") },
                    supportingText = { Text("Use a decimal point, for example 1234.5.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                PillButton(
                    label = if (active == null) "Start trip" else "End trip",
                    onClick = {
                        if (active == null) onStartTrip(odometer) else onEndTrip(odometer)
                    },
                    enabled = enabled && odometer.isNotBlank(),
                )
            }
        }

        item { SectionHeader("Saved trips (${state.trips.size}${if (state.hasMoreTrips) "+" else ""})") }
        if (state.trips.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item { EmptyState("No trips yet. Start a manual trip above to create your first record.") }
        }
        items(state.trips.filter { it.endTime != null }) { trip -> TripRecordCard(trip) }
        if (state.hasMoreTrips) {
            item {
                PillButton(
                    label = "Load more trips",
                    onClick = onLoadMore,
                    enabled = enabled,
                    style = PillStyle.Outlined,
                )
            }
        }
    }
}

@Composable
private fun TripRecordCard(trip: Trip) {
    PulsarCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    formatRecordDateTime(trip.startTime),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                trip.endTime?.let {
                    Text(
                        "Ended ${formatRecordDateTime(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusPill(
                text = trip.distanceKm?.let { "${formatRecordNumber(it)} km" } ?: "No distance",
                tone = if (trip.distanceKm != null) StatusTone.Active else StatusTone.Neutral,
            )
        }
        PulsarDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricBlock(
                label = "Start odometer",
                value = "${formatRecordNumber(trip.startOdometer)} km",
                modifier = Modifier.weight(1f),
            )
            MetricBlock(
                label = "End odometer",
                value = trip.endOdometer?.let { "${formatRecordNumber(it)} km" } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val duration = Duration.ofSeconds(trip.durationSeconds)
            MetricBlock(
                label = "Duration",
                value = "${duration.toHours()} h ${duration.toMinutes() % 60} min",
                modifier = Modifier.weight(1f),
            )
            MetricBlock(
                label = "Source",
                value = "Manual record",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "This phone only · no cloud sync",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatRecordNumber(value: Double): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 2
    }.format(value)

internal fun formatRecordDateTime(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

internal fun formatRecordDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
