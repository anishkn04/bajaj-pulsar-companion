package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    onBack: () -> Unit,
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
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to home") }
            Text("Trip log", style = MaterialTheme.typography.headlineLarge)
        }
        item { LocalDataStatus(state, onClearError, onRetry) }
        item {
            Text(
                "Manual, local-only records. Enter the odometer from your bike; " +
                    "trips are not recorded over Bluetooth or synced to the cloud.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (active == null) "Start a manual trip" else "Trip in progress",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (active != null) {
                        Text("Started ${formatRecordDateTime(active.startTime)}")
                        Text("Start odometer: ${formatRecordNumber(active.startOdometer)} km")
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
                    )
                    Button(
                        onClick = {
                            if (active == null) onStartTrip(odometer) else onEndTrip(odometer)
                        },
                        enabled = enabled && odometer.isNotBlank(),
                    ) { Text(if (active == null) "Start trip" else "End trip") }
                }
            }
        }
        item { Text("Saved trips", style = MaterialTheme.typography.titleLarge) }
        if (state.trips.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item { Text("No trips yet. Start a manual trip above to create your first record.") }
        }
        items(state.trips.filter { it.endTime != null }) { trip -> TripRecordCard(trip) }
        if (state.hasMoreTrips) {
            item {
                Button(onClick = onLoadMore, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Load more trips")
                }
            }
        }
    }
}

@Composable
private fun TripRecordCard(trip: Trip) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatRecordDateTime(trip.startTime), style = MaterialTheme.typography.titleMedium)
            trip.endTime?.let { Text("Ended ${formatRecordDateTime(it)}") }
            Text("Distance: ${trip.distanceKm?.let { "${formatRecordNumber(it)} km" } ?: "Not recorded"}")
            val duration = Duration.ofSeconds(trip.durationSeconds)
            Text(
                "Duration: ${formatRecordNumber(duration.toHours().toDouble())} h " +
                    "${formatRecordNumber((duration.toMinutes() % 60).toDouble())} min " +
                    "${formatRecordNumber((duration.seconds % 60).toDouble())} s",
            )
            Text(
                "Odometer: ${formatRecordNumber(trip.startOdometer)} → " +
                    "${trip.endOdometer?.let(::formatRecordNumber) ?: "—"} km",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Manual record · This phone only", style = MaterialTheme.typography.labelSmall)
        }
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
