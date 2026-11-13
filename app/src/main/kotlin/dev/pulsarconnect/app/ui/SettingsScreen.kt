package dev.pulsarconnect.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.app.security.AppLockSettings
import dev.pulsarconnect.app.security.BiometricStatus
import dev.pulsarconnect.app.security.checkBiometricStatus
import dev.pulsarconnect.app.ui.theme.ThemeMode
import dev.pulsarconnect.app.ui.theme.ThemeSettings
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
    themeSettings: ThemeSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    appLockSettings: AppLockSettings,
    onAppLockChange: (Boolean) -> Unit,
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
    val notificationGranted = notificationPermissionGranted(context)

    var biometricStatus by remember { mutableStateOf(checkBiometricStatus(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                biometricStatus = checkBiometricStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

        SectionCard(title = "Appearance") {
            Text(
                "System follows this phone's light or dark setting. Your choice is remembered " +
                    "after the app restarts and sign-out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    PillButton(
                        label = when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                        onClick = { onThemeModeChange(mode) },
                        modifier = Modifier.weight(1f),
                        style = if (mode == themeSettings.mode) PillStyle.Filled else PillStyle.Outlined,
                    )
                }
            }
            PulsarDivider()
            PulsarSwitchRow(
                title = "Use system accent colors",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Match the wallpaper palette on Android 12 and newer."
                } else {
                    "Available on Android 12 and newer; this phone keeps Pulsar colors."
                },
                checked = themeSettings.dynamicColor,
                onCheckedChange = onDynamicColorChange,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            )
        }

        SectionCard(title = "App lock") {
            PulsarSwitchRow(
                title = "Require biometrics to open",
                description = when (biometricStatus) {
                    BiometricStatus.AVAILABLE ->
                        "Ask for your fingerprint or face after the app has been in the background."
                    BiometricStatus.NOT_ENROLLED ->
                        "Add a fingerprint or face unlock in system settings first."
                    BiometricStatus.NO_HARDWARE -> "This phone has no biometric sensor."
                    BiometricStatus.TEMPORARILY_UNAVAILABLE ->
                        "Biometrics are unavailable right now. Try again later."
                    BiometricStatus.UNKNOWN -> "Biometric unlock is not available on this phone."
                },
                checked = appLockSettings.requireBiometric,
                onCheckedChange = onAppLockChange,
                enabled = biometricStatus == BiometricStatus.AVAILABLE ||
                    appLockSettings.requireBiometric,
            )
            Text(
                "App lock hides this screen only. Your local database is still not separately " +
                    "encrypted, so someone with access to this phone's storage could read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                onAction = { openAppNotificationSettings(context) },
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
