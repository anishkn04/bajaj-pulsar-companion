package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pulsarconnect.core.data.isDue
import dev.pulsarconnect.core.model.ServiceReminder
import kotlinx.coroutines.delay

@Composable
internal fun RemindersScreen(
    state: UiState.SignedIn,
    onAddReminder: (String, String, String) -> Unit,
    onCompleteReminder: (Long) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onNotificationsRefresh: () -> Unit,
) {
    var title by rememberSaveable(state.vin) { mutableStateOf("") }
    var odometer by rememberSaveable(state.vin) { mutableStateOf("") }
    var date by rememberSaveable(state.vin) { mutableStateOf("") }
    var submittedCount by rememberSaveable(state.vin) { mutableStateOf<Int?>(null) }
    // Preserve input on validation errors; clear only after a new reminder is observed.
    LaunchedEffect(state.reminders.size) {
        submittedCount?.let { count ->
            if (state.reminders.size > count) {
                title = ""
                odometer = ""
                date = ""
                submittedCount = null
            }
        }
    }
    val now = rememberCurrentTimeMillis()
    val enabled = !state.busy && !state.localLoading && !state.localLoadFailed
    val pending = state.reminders.filter { !it.completed }
    val completed = state.reminders.filter { it.completed }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Service",
                title = "Reminders",
                subtitle = "Track maintenance against dates and your latest recorded odometer. " +
                    "This is not live bike data.",
            )
        }
        item { LocalDataStatus(state, onClearError, onRetry) }
        item {
            ReminderNotificationSettings(
                enabled = state.reminderNotificationsEnabled,
                onEnabledChange = onNotificationsEnabledChange,
                onNotificationsRefresh = onNotificationsRefresh,
            )
        }
        item {
            Text(
                state.lastOdometer?.let { "Latest recorded odometer: ${formatRecordNumber(it)} km (not live)." }
                    ?: "Odometer unknown. Distance-based due status needs a manual trip reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            PulsarCard {
                Text(
                    "Add reminder",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    placeholder = { Text("Oil change") },
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Due odometer (km, optional)") },
                    placeholder = { Text("5000.0") },
                    supportingText = { Text("Use a decimal point for fractional kilometres.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Due date (optional)") },
                    placeholder = { Text("yyyy-MM-dd") },
                    supportingText = { Text("ISO date, for example 2026-12-31. Uses this device’s time zone.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                Text(
                    "Set an odometer, a date, or both. When both are set, the reminder is due " +
                        "whichever comes first. Odometer checks use your latest recorded reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillButton(
                    label = "Add reminder",
                    onClick = {
                        submittedCount = state.reminders.size
                        onAddReminder(title, odometer, date)
                    },
                    enabled = enabled && title.isNotBlank(),
                )
            }
        }

        item { SectionHeader("Pending (${pending.size})") }
        if (pending.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item { EmptyState("No pending reminders. Add your next service above.") }
        }
        items(pending) { reminder ->
            ReminderRecordCard(reminder, state.lastOdometer, now, enabled, onCompleteReminder)
        }

        item { SectionHeader("Completed (${completed.size})") }
        if (completed.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item { EmptyState("No completed reminders yet.") }
        }
        items(completed) { reminder ->
            ReminderRecordCard(reminder, state.lastOdometer, now, enabled, onCompleteReminder)
        }
    }
}

@Composable
private fun ReminderRecordCard(
    reminder: ServiceReminder,
    lastOdometer: Double?,
    now: Long,
    enabled: Boolean,
    onComplete: (Long) -> Unit,
) {
    val due = reminder.isDue(lastOdometer ?: Double.NaN, now)
    PulsarCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                reminder.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            val (label, tone) = when {
                reminder.completed -> "Completed" to StatusTone.Complete
                due -> "Due" to StatusTone.Error
                reminder.dueOdometerKm != null && lastOdometer == null -> "Unknown" to StatusTone.Warning
                else -> "Not due" to StatusTone.Neutral
            }
            StatusPill(text = label, tone = tone)
        }
        Text(
            when {
                reminder.completed -> "Completed"
                due -> "Due · based on date or recorded odometer"
                reminder.dueOdometerKm != null && lastOdometer == null -> "Odometer status unknown"
                else -> "Not due based on saved readings and date"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reminder.dueOdometerKm != null || reminder.dueDateMillis != null || reminder.completedAtMillis != null) {
            PulsarDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                reminder.dueOdometerKm?.let {
                    MetricBlock(
                        label = "Due odometer",
                        value = "${formatRecordNumber(it)} km",
                        modifier = Modifier.weight(1f),
                    )
                }
                reminder.dueDateMillis?.let {
                    MetricBlock(
                        label = "Due date",
                        value = formatRecordDate(it),
                        modifier = Modifier.weight(1f),
                    )
                }
                reminder.completedAtMillis?.let {
                    MetricBlock(
                        label = "Completed",
                        value = formatRecordDateTime(it),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!reminder.completed) {
            PillButton(
                label = "Mark done",
                onClick = { reminder.id?.let(onComplete) },
                enabled = enabled && reminder.id != null,
                style = PillStyle.Tonal,
            )
        }
    }
}

@Composable
internal fun rememberCurrentTimeMillis(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }
    return now
}
