package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pulsarconnect.core.data.isDue
import dev.pulsarconnect.core.model.ServiceReminder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun RemindersScreen(
    state: UiState.SignedIn,
    onAddReminder: (String, String, String, String, String) -> Unit,
    onEditReminder: (Long, String, String, String, String, String, String) -> Unit,
    onDeleteReminder: (Long) -> Unit,
    onSnoozeReminder: (Long, Long, Double) -> Unit,
    onReopenReminder: (Long) -> Unit,
    onCompleteReminder: (Long) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onNotificationsRefresh: () -> Unit,
) {
    var title by rememberSaveable(state.vin) { mutableStateOf("") }
    var odometer by rememberSaveable(state.vin) { mutableStateOf("") }
    var date by rememberSaveable(state.vin) { mutableStateOf("") }
    var repeatKm by rememberSaveable(state.vin) { mutableStateOf("") }
    var repeatDays by rememberSaveable(state.vin) { mutableStateOf("") }
    var submittedCount by rememberSaveable(state.vin) { mutableStateOf<Int?>(null) }
    var editingReminder by remember { mutableStateOf<ServiceReminder?>(null) }
    var deletingReminder by remember { mutableStateOf<ServiceReminder?>(null) }
    // Preserve input on validation errors; clear only after a new reminder is observed.
    LaunchedEffect(state.reminders.size) {
        submittedCount?.let { count ->
            if (state.reminders.size > count) {
                title = ""
                odometer = ""
                date = ""
                repeatKm = ""
                repeatDays = ""
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
                OutlinedTextField(
                    value = repeatKm,
                    onValueChange = { repeatKm = it },
                    label = { Text("Repeat every (km, optional)") },
                    placeholder = { Text("3000.0") },
                    supportingText = { Text("Needs a due odometer; the next occurrence is created after completion.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = repeatDays,
                    onValueChange = { repeatDays = it },
                    label = { Text("Repeat every (days, optional)") },
                    placeholder = { Text("180") },
                    supportingText = { Text("Needs a due date; the next occurrence is created after completion.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                Text(
                    "Set an odometer, a date, or both. When both are set, the reminder is due " +
                        "whichever comes first. Odometer checks use your latest recorded reading. " +
                        "Recurring reminders re-arm their next date or distance when marked done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillButton(
                    label = "Add reminder",
                    onClick = {
                        submittedCount = state.reminders.size
                        onAddReminder(title, odometer, date, repeatKm, repeatDays)
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
            ReminderRecordCard(
                reminder = reminder,
                lastOdometer = state.lastOdometer,
                now = now,
                enabled = enabled,
                onComplete = onCompleteReminder,
                onEdit = { editingReminder = reminder },
                onDelete = { deletingReminder = reminder },
                onSnooze = { days, kilometres -> reminder.id?.let { onSnoozeReminder(it, days, kilometres) } },
                onReopen = null,
            )
        }

        item { SectionHeader("Completed (${completed.size})") }
        if (completed.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item { EmptyState("No completed reminders yet.") }
        }
        items(completed) { reminder ->
            ReminderRecordCard(
                reminder = reminder,
                lastOdometer = state.lastOdometer,
                now = now,
                enabled = enabled,
                onComplete = onCompleteReminder,
                onEdit = { editingReminder = reminder },
                onDelete = { deletingReminder = reminder },
                onSnooze = { _, _ -> },
                onReopen = { reminder.id?.let(onReopenReminder) },
            )
        }
    }

    editingReminder?.let { reminder ->
        EditReminderDialog(
            reminder = reminder,
            onDismiss = { editingReminder = null },
            onConfirm = { newTitle, description, newOdometer, newDate, newRepeatKm, newRepeatDays ->
                editingReminder = null
                reminder.id?.let {
                    onEditReminder(it, newTitle, description, newOdometer, newDate, newRepeatKm, newRepeatDays)
                }
            },
        )
    }
    deletingReminder?.let { reminder ->
        AlertDialog(
            onDismissRequest = { deletingReminder = null },
            title = { Text("Delete this reminder?") },
            text = { Text("“${reminder.title}” will be removed permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingReminder = null
                        reminder.id?.let(onDeleteReminder)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingReminder = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ReminderRecordCard(
    reminder: ServiceReminder,
    lastOdometer: Double?,
    now: Long,
    enabled: Boolean,
    onComplete: (Long) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: (Long, Double) -> Unit,
    onReopen: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Reminder actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit reminder") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    if (!reminder.completed && reminder.dueDateMillis != null) {
                        DropdownMenuItem(
                            text = { Text("Snooze 1 day") },
                            onClick = {
                                menuOpen = false
                                onSnooze(1, 0.0)
                            },
                        )
                    }
                    if (!reminder.completed && reminder.dueOdometerKm != null) {
                        DropdownMenuItem(
                            text = { Text("Snooze 500 km") },
                            onClick = {
                                menuOpen = false
                                onSnooze(0, 500.0)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete reminder") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        reminder.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val repeatLabel = listOfNotNull(
            reminder.repeatEveryKm?.let { "every ${formatRecordNumber(it)} km" },
            reminder.repeatEveryDays?.let { if (it == 1L) "every day" else "every $it days" },
        ).joinToString(" and ")
        if (repeatLabel.isNotEmpty()) {
            Text(
                "Repeats $repeatLabel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        } else if (onReopen != null) {
            PillButton(
                label = "Undo complete",
                onClick = onReopen,
                enabled = enabled && reminder.id != null,
                style = PillStyle.Outlined,
            )
        }
    }
}

@Composable
private fun EditReminderDialog(
    reminder: ServiceReminder,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit,
) {
    var title by remember(reminder.id) { mutableStateOf(reminder.title) }
    var description by remember(reminder.id) { mutableStateOf(reminder.description.orEmpty()) }
    var odometer by remember(reminder.id) {
        mutableStateOf(reminder.dueOdometerKm?.let(::editableOdometer).orEmpty())
    }
    var date by remember(reminder.id) {
        mutableStateOf(reminder.dueDateMillis?.let(::editableDate).orEmpty())
    }
    var repeatKm by remember(reminder.id) {
        mutableStateOf(reminder.repeatEveryKm?.let(::editableOdometer).orEmpty())
    }
    var repeatDays by remember(reminder.id) {
        mutableStateOf(reminder.repeatEveryDays?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var validationError by remember(reminder.id) { mutableStateOf<String?>(null) }

    fun submit() {
        val odometerValue = odometer.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        val repeatKmValue = repeatKm.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        val repeatDaysValue = repeatDays.trim().takeIf(String::isNotEmpty)?.toLongOrNull()
        validationError = when {
            title.isBlank() -> "Enter a reminder title."
            odometer.isNotBlank() && (odometerValue == null || !odometerValue.isFinite() || odometerValue < 0) ->
                "Enter a finite, nonnegative odometer reading in km."
            repeatKm.isNotBlank() && (repeatKmValue == null || !repeatKmValue.isFinite() || repeatKmValue <= 0) ->
                "Enter a repeat distance greater than 0 km."
            repeatDays.isNotBlank() && (repeatDaysValue == null || repeatDaysValue <= 0) ->
                "Enter a repeat interval of at least one day."
            repeatKm.isNotBlank() && odometer.isBlank() ->
                "Set a repeat distance only when the reminder has a due odometer."
            repeatDays.isNotBlank() && date.isBlank() ->
                "Set a repeat number of days only when the reminder has a due date."
            odometer.isBlank() && date.isBlank() -> "Enter a due odometer or date (or both)."
            else -> null
        }
        if (validationError == null) onConfirm(title, description, odometer, date, repeatKm, repeatDays)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Due odometer (km, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Due date (optional)") },
                    placeholder = { Text("yyyy-MM-dd") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repeatKm,
                    onValueChange = { repeatKm = it },
                    label = { Text("Repeat every (km, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repeatDays,
                    onValueChange = { repeatDays = it },
                    label = { Text("Repeat every (days, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
internal fun rememberCurrentTimeMillis(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }
    return now
}

private fun editableDate(millis: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(),
    )
