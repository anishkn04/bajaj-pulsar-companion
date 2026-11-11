package dev.pulsarconnect.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.data.LocalBackup
import dev.pulsarconnect.core.data.LocalBackupCodec
import dev.pulsarconnect.core.data.RestoreMode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    bleState: BleUiState,
    backupBusy: Boolean,
    onMirroringChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    onBootChange: (Boolean) -> Unit,
    onStopMirroring: () -> Unit,
    onExportBackup: (Boolean, suspend (String) -> Unit) -> Unit,
    onRestoreBackup: (String, RestoreMode) -> Unit,
    onReportError: (String) -> Unit,
    onOpenBatteryOptimization: () -> Unit,
) {
    val context = LocalContext.current
    val notificationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    var redactVin by rememberSaveable { mutableStateOf(false) }
    var redactForPendingExport by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<RestorePreview?>(null) }
    val scope = rememberCoroutineScope()

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val redact = redactForPendingExport
        onExportBackup(redact) { json ->
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Could not open the selected file for writing.")
                stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val encoded = try {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Could not open the selected file.")
                    require(bytes.size <= MAX_BACKUP_BYTES) {
                        "That file is too large to be a Pulsar Connect backup."
                    }
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (error: Exception) {
                onReportError(error.message ?: "Could not read that file.")
                return@launch
            }
            val backup = try {
                LocalBackupCodec.decode(encoded)
            } catch (error: IllegalArgumentException) {
                onReportError(error.message ?: "That file is not a valid Pulsar Connect backup.")
                return@launch
            }
            restorePreview = RestorePreview(encoded, backup)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader(
            title = "Settings",
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

        SectionCard(title = "Backup & restore") {
            Text(
                "Export saves this bike's trips, ride samples, and reminders as a JSON file. " +
                    "Credentials, tokens, notification content, and Bluetooth addresses are never included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PulsarSwitchRow(
                title = "Redact VIN in export",
                description = "Replace the VIN with a placeholder before sharing the file.",
                checked = redactVin,
                onCheckedChange = { redactVin = it },
                enabled = !backupBusy,
            )
            PulsarDivider()
            PillButton(
                label = "Export records",
                onClick = {
                    redactForPendingExport = redactVin
                    createDocument.launch("pulsar-connect-backup.json")
                },
                enabled = !backupBusy,
            )
            PillButton(
                label = "Restore from file",
                onClick = { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !backupBusy,
                style = PillStyle.Outlined,
            )
            Text(
                "Restoring affects only the bike you are signed in to. Merge keeps current records " +
                    "and adds missing ones; Replace removes this bike's current records first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }

    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { restorePreview = null },
            title = { Text("Restore records?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This file contains ${preview.backup.trips.size} trips, " +
                            "${preview.backup.telemetry.size} ride samples, and " +
                            "${preview.backup.reminders.size} reminders.",
                    )
                    if (preview.backup.redacted) {
                        Text(
                            "Its VIN was redacted, so the records will be restored for the bike you " +
                                "are signed in to.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Merge keeps your current records and adds missing ones. Replace deletes this " +
                            "bike's current trips and reminders first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            restorePreview = null
                            onRestoreBackup(preview.encoded, RestoreMode.MERGE)
                        },
                    ) {
                        Text("Merge")
                    }
                    TextButton(
                        onClick = {
                            restorePreview = null
                            onRestoreBackup(preview.encoded, RestoreMode.REPLACE)
                        },
                    ) {
                        Text("Replace", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { restorePreview = null }) { Text("Cancel") }
            },
        )
    }
}

private data class RestorePreview(val encoded: String, val backup: LocalBackup)

private const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
