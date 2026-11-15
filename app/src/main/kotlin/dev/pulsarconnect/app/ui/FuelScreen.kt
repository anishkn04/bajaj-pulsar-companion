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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
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
import dev.pulsarconnect.core.data.FuelEconomy
import dev.pulsarconnect.core.data.FuelInterval
import dev.pulsarconnect.core.data.fuelEconomy
import dev.pulsarconnect.core.model.FuelFillUp

@Composable
internal fun FuelScreen(
    state: UiState.SignedIn,
    onAddFillUp: (String, String, Boolean) -> Unit,
    onDeleteFillUp: (Long) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var logging by rememberSaveable(state.vin) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FuelFillUp?>(null) }
    val economy = remember(state.fillUps) { fuelEconomy(state.fillUps) }
    val enabled = !state.busy && !state.localLoading && !state.localLoadFailed

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to home",
                    )
                }
                ScreenHeader(
                    eyebrow = "Fuel",
                    title = "Economy",
                    subtitle = "Full-to-full consumption from your manual fill-ups.",
                )
            }
        }
        item { LocalDataStatus(state, onClearError, onRetry) }
        item { FuelEconomyCard(economy) }
        item {
            PulsarCard {
                Text(
                    text = "Log fill-up",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Record the odometer and litres at the pump. Mark a fill-up as full " +
                        "when the tank is filled to the same level as the previous full tank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillButton(
                    label = "Log fill-up",
                    onClick = { logging = true },
                    enabled = enabled,
                )
            }
        }
        item { SectionHeader("Fill-ups (${state.fillUps.size})") }
        if (state.fillUps.isEmpty() && !state.localLoading && !state.localLoadFailed) {
            item {
                EmptyState(
                    "No fill-ups yet. Log two full-tank fill-ups to see fuel economy. " +
                        "Partial fill-ups between them are counted automatically.",
                )
            }
        }
        items(state.fillUps) { fillUp ->
            FuelFillUpCard(
                fillUp = fillUp,
                enabled = enabled,
                onDelete = { deleting = fillUp },
            )
        }
    }

    if (logging) {
        LogFillUpDialog(
            prefillOdometer = state.lastOdometer,
            previousOdometer = state.fillUps.maxOfOrNull { it.odometerKm },
            onDismiss = { logging = false },
            onConfirm = { odometer, liters, fullTank ->
                logging = false
                onAddFillUp(odometer, liters, fullTank)
            },
        )
    }
    deleting?.let { fillUp ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this fill-up?") },
            text = {
                Text(
                    "The ${formatRecordNumber(fillUp.liters)} L fill-up at " +
                        "${formatRecordNumber(fillUp.odometerKm)} km will be removed permanently.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        fillUp.id?.let(onDeleteFillUp)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FuelEconomyCard(economy: FuelEconomy) {
    val latest = economy.latest
    PulsarCard {
        Text(
            text = "Fuel economy",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (latest == null) {
            Text(
                text = "Not enough full fill-ups yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Log two full-tank fill-ups. Partial fill-ups in between count toward the " +
                    "next full-to-full interval.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricBlock(
                    label = "Latest interval",
                    value = "${formatRecordNumber(latest.lPer100Km)} L/100 km",
                    modifier = Modifier.weight(1f),
                )
                MetricBlock(
                    label = "All intervals",
                    value = economy.averageLPer100Km
                        ?.let { "${formatRecordNumber(it)} L/100 km" }
                        ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
            PulsarDivider()
            Text(
                text = intervalDetail(latest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (economy.intervals.size > 1) {
                Text(
                    text = "Across ${economy.intervals.size} intervals: " +
                        "${formatRecordNumber(economy.totalDistanceKm)} km on " +
                        "${formatRecordNumber(economy.totalLiters)} L.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FuelFillUpCard(
    fillUp: FuelFillUp,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    PulsarCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatRecordDate(fillUp.filledAtMillis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${formatRecordNumber(fillUp.odometerKm)} km · " +
                        "${formatRecordNumber(fillUp.liters)} L",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (fillUp.fullTank) "Full" else "Partial",
                tone = if (fillUp.fullTank) StatusTone.Active else StatusTone.Neutral,
            )
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete fill-up",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LogFillUpDialog(
    prefillOdometer: Double?,
    previousOdometer: Double?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit,
) {
    var odometer by remember(prefillOdometer) {
        mutableStateOf(prefillOdometer?.let(::editableOdometer).orEmpty())
    }
    var liters by remember { mutableStateOf("") }
    var fullTank by remember { mutableStateOf(true) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val odometerValue = odometer.trim().toDoubleOrNull()
        val litersValue = liters.trim().toDoubleOrNull()
        validationError = when {
            odometerValue == null || !odometerValue.isFinite() || odometerValue < 0 ->
                "Enter a valid odometer reading."
            previousOdometer != null && odometerValue <= previousOdometer ->
                "Odometer must be higher than the last fill-up " +
                    "(${formatRecordNumber(previousOdometer)} km)."
            litersValue == null || !litersValue.isFinite() || litersValue <= 0 ->
                "Enter litres greater than 0."
            else -> null
        }
        if (validationError == null) onConfirm(odometer, liters, fullTank)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log fill-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Odometer (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = liters,
                    onValueChange = { liters = it },
                    label = { Text("Litres added") },
                    placeholder = { Text("10.5") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                PulsarSwitchRow(
                    title = "Full tank",
                    description = "Closes a full-to-full interval. Leave off for a partial fill.",
                    checked = fullTank,
                    onCheckedChange = { fullTank = it },
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

/** Compact value used by the Home card. */
internal fun fuelSummaryValue(fillUps: List<FuelFillUp>): String =
    fuelEconomy(fillUps).latest
        ?.let { "${formatRecordNumber(it.lPer100Km)} L/100 km" }
        ?: "Not enough full fill-ups yet"

/** Supporting line used by the Home card. */
internal fun fuelSummaryDetail(fillUps: List<FuelFillUp>): String {
    val latest = fuelEconomy(fillUps).latest
        ?: return "Log two full-tank fill-ups to calculate consumption."
    return "${formatRecordNumber(latest.distanceKm)} km on " +
        "${formatRecordNumber(latest.liters)} L since the last full tank."
}

private fun intervalDetail(interval: FuelInterval): String =
    "${formatRecordNumber(interval.distanceKm)} km on " +
        "${formatRecordNumber(interval.liters)} L · " +
        "${formatRecordNumber(interval.kmPerLitre)} km/L"
