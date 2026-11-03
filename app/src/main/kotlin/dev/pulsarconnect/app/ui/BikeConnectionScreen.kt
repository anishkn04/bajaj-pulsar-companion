package dev.pulsarconnect.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.pulsarconnect.app.ble.AndroidBlePermissions
import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.ControlProtocol
import dev.pulsarconnect.core.ble.ClusterControlState
import dev.pulsarconnect.core.ble.DiscoveredDevice

private enum class PendingBleAction { SCAN, CONNECT }

@Composable
internal fun BikeConnectionScreen(
    bleState: BleUiState,
    onScan: () -> Unit,
    onSelect: (DiscoveredDevice) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onPhoneStatusChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<PendingBleAction?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        val action = pendingAction
        pendingAction = null
        onRefresh()
        if (granted) {
            when (action) {
                PendingBleAction.SCAN -> onScan()
                PendingBleAction.CONNECT -> onConnect()
                null -> Unit
            }
        }
    }

    var phonePermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phonePermissionGranted = granted
        onRefresh()
    }

    fun request(action: PendingBleAction) {
        pendingAction = action
        permissionLauncher.launch(AndroidBlePermissions.androidPermissions(Build.VERSION.SDK_INT))
    }

    fun scanOrRequest() {
        if (bleState.missingPermissions.isEmpty()) onScan() else request(PendingBleAction.SCAN)
    }

    fun connectOrRequest() {
        if (bleState.missingPermissions.isEmpty()) onConnect() else request(PendingBleAction.CONNECT)
    }

    val connection = bleState.connection
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to home") }
            Text("Bike link", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            Text(
                "Connect to your bike over Bluetooth. This build discovers and shows raw GATT " +
                    "services only; cluster values are not decoded until the protocol is captured.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (connection is ConnectionState.Connecting ||
                            connection is ConnectionState.Discovering ||
                            connection is ConnectionState.Reconnecting
                        ) {
                            CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                        }
                        Column {
                            Text(
                                bleStatusValue(connection),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                bleStatusDetail(bleState),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (bleState.adapterState == AdapterState.OFF) {
                        Text("Bluetooth is turned off.", color = MaterialTheme.colorScheme.error)
                        Button(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        }) { Text("Open Bluetooth settings") }
                    }

                    if (bleState.missingPermissions.isNotEmpty()) {
                        Text(
                            "Nearby-device access is required to scan and connect.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { request(PendingBleAction.SCAN) }) {
                            Text("Grant Bluetooth access")
                        }
                    }

                    when (connection) {
                        is ConnectionState.Connected -> {
                            Button(onClick = onDisconnect) { Text("Disconnect") }
                        }
                        is ConnectionState.Failed -> {
                            Button(onClick = onRetry) { Text("Retry") }
                        }
                        is ConnectionState.MissingPermissions -> {
                            Button(onClick = { request(PendingBleAction.CONNECT) }) {
                                Text("Grant access and connect")
                            }
                        }
                        is ConnectionState.BluetoothUnavailable -> {
                            Button(onClick = onRetry) { Text("Retry") }
                        }
                        else -> {
                            val selected = bleState.selected
                            if (selected != null) {
                                Button(
                                    onClick = ::connectOrRequest,
                                    enabled = !bleState.scanning,
                                ) { Text("Connect") }
                            }
                        }
                    }
                }
            }
        }

        if (connection is ConnectionState.Connected) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    SwitchRow(
                        title = "Battery & signal",
                        description = "Sends only the verified idle battery/signal frame. It never sends calls, contacts or messages.",
                        checked = bleState.phoneStatusEnabled,
                        onCheckedChange = onPhoneStatusChange,
                        enabled = bleState.phoneStatusAvailable,
                    )
                    if (!bleState.phoneStatusAvailable) {
                        Text(
                            "This bike did not expose the writable phone-status endpoint, so nothing will be sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        if (bleState.phoneStatusSending) {
                            Text(
                                "Sending battery and signal every second.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (phonePermissionGranted) {
                            Text(
                                "Phone-state permission is granted; signal appears when the network reports it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Battery works without permission. Allow phone state only to mirror cellular signal too.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                                },
                            ) { Text("Allow signal") }
                        }
                    }
                    bleState.phoneStatusError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        bleState.error?.let { error ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onClearError) { Text("Dismiss") }
                    }
                }
            }
        }

        bleState.selected?.let { selected ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            selected.name ?: "Unnamed Bluetooth device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            selected.id,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SwitchRow(
                            title = "Auto-connect",
                            description = "Only while this screen is in the foreground.",
                            checked = bleState.autoConnect,
                            onCheckedChange = onAutoConnectChange,
                        )
                        TextButton(onClick = onClearSelection) { Text("Forget this bike") }
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Turn the bike ignition and kill switch on, then scan. " +
                            "Bluetooth addresses stay on this phone and are never exported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = ::scanOrRequest,
                        enabled = !bleState.scanning && bleState.missingPermissions.isEmpty(),
                    ) {
                        Text(if (bleState.scanning) "Scanning…" else "Scan for bikes")
                    }
                    if (bleState.devices.isEmpty()) {
                        Text(
                            if (bleState.scanning) "Listening for nearby Bluetooth devices…"
                            else "No devices found yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(bleState.devices, key = { it.id }) { device ->
            DeviceRow(
                device = device,
                selected = bleState.selected?.id == device.id,
                onSelect = { onSelect(device) },
            )
        }

        if (connection is ConnectionState.Connected) {
            item {
                Text(
                    "GATT inspector",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (connection.services.isEmpty()) {
                item {
                    Text(
                        "The bike did not expose any GATT services.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(connection.services, key = { it.uuid.toString() }) { service ->
                ServiceCard(service)
            }
            bleState.controlState.frame?.let { frame ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "The cluster's CONTROL characteristic is polled every 700 ms. Values are counters only; this app performs no actions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Volume", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.volume}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Call accept", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.callAccept}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Call reject", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.callReject}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Next track", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.next}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Previous track", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.previous}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Play/pause", style = MaterialTheme.typography.bodySmall)
                                    Text("${if (frame.resume > 0) 1 else 0} / ${if (frame.pause > 0) 1 else 0}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Missed-call read", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.missedCallRead}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Alert read", style = MaterialTheme.typography.bodySmall)
                                    Text("${frame.alertRead}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Text(
                                "Polling: ${if (bleState.controlState.polling) "on" else "off"} · " +
                                    "Response size: ${ControlProtocol.RESPONSE_SIZE} bytes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    device.name ?: "Unnamed Bluetooth device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    device.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${device.rssi} dBm${if (device.bonded) " · paired" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSelect) {
                Text(if (selected) "Selected" else "Use")
            }
        }
    }
}

@Composable
private fun ServiceCard(service: BleService) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Service",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                service.uuid.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (service.characteristics.isEmpty()) {
                Text(
                    "No characteristics exposed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            service.characteristics.forEach { characteristic ->
                val properties = buildList {
                    if (characteristic.canRead) add("read")
                    if (characteristic.canWrite) add("write")
                    if (characteristic.canNotify) add("notify")
                    if (characteristic.canIndicate) add("indicate")
                }.joinToString(", ").ifEmpty { "no access" }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        characteristic.uuid.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        properties,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
