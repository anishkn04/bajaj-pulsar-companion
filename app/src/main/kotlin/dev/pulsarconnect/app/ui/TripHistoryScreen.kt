package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    onEditTrip: (Long, String, String) -> Unit,
    onDeleteTrip: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = state.activeTrip
    var odometer by rememberSaveable(state.vin, active?.id, active?.startTime) {
        mutableStateOf("")
    }
    var editingTrip by remember { mutableStateOf<Trip?>(null) }
    var deletingTrip by remember { mutableStateOf<Trip?>(null) }
    val enabled = !state.busy && !state.localLoading && !state.localLoadFailed
    val completedTrips = state.trips.filter { it.endTime != null }
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
                subtitle = "Enter the odometer from your bike.",
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
                if (active != null) {
                    PillButton(
                        label = "Discard trip",
                        onClick = { deletingTrip = active },
                        enabled = enabled,
                        style = PillStyle.Outlined,
                    )
                }
            }
        }

        item { SectionHeader("Saved trips (${completedTrips.size}${if (state.hasMoreTrips) "+" else ""})") }
        if (completedTrips.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item { EmptyState("No trips yet. Start a manual trip above to create your first record.") }
        }
        items(completedTrips) { trip ->
            TripRecordCard(
                trip = trip,
                onEdit = { editingTrip = trip },
                onDelete = { deletingTrip = trip },
            )
        }
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

    editingTrip?.let { trip ->
        TripOdometerDialog(
            trip = trip,
            onDismiss = { editingTrip = null },
            onConfirm = { start, end ->
                editingTrip = null
                onEditTrip(trip.id!!, start, end)
            },
        )
    }
    deletingTrip?.let { trip ->
        DeleteTripDialog(
            discarding = trip.endTime == null,
            onDismiss = { deletingTrip = null },
            onConfirm = {
                deletingTrip = null
                onDeleteTrip(trip.id!!)
            },
        )
    }
}

@Composable
private fun TripRecordCard(
    trip: Trip,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Trip actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit odometer") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete trip") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
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

@Composable
private fun TripOdometerDialog(
    trip: Trip,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var start by remember(trip.id) { mutableStateOf(editableOdometer(trip.startOdometer)) }
    var end by remember(trip.id) { mutableStateOf(trip.endOdometer?.let(::editableOdometer) ?: "") }
    var validationError by remember(trip.id) { mutableStateOf<String?>(null) }

    fun submit() {
        val startValue = start.trim().toDoubleOrNull()
        val endValue = end.trim().toDoubleOrNull()
        validationError = when {
            startValue == null || !startValue.isFinite() || startValue < 0 -> "Enter a valid start odometer."
            endValue == null || !endValue.isFinite() || endValue < 0 -> "Enter a valid end odometer."
            endValue < startValue -> "End odometer must not be lower than the start odometer."
            else -> null
        }
        if (validationError == null) onConfirm(start, end)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit trip odometer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Correct a mistyped reading. Distance and average speed are recalculated.")
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Start odometer (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("End odometer (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                validationError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeleteTripDialog(
    discarding: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (discarding) "Discard this trip?" else "Delete this trip?") },
        text = {
            Text(
                if (discarding) {
                    "The in-progress trip will be removed without saving a record."
                } else {
                    "The saved trip and any stored ride samples will be removed. You can undo " +
                        "right after deleting."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(
                    if (discarding) "Discard" else "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun editableOdometer(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

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
