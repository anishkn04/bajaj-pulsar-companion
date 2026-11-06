package dev.pulsarconnect.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pulsarconnect.app.ble.AndroidBlePermissions
import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.ClusterControlState
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.ControlProtocol
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
) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<PendingBleAction?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        val action = pendingAction
        pendingAction = null
        if (granted) {
            when (action) {
                PendingBleAction.SCAN -> onScan()
                PendingBleAction.CONNECT -> onConnect()
                null -> Unit
            }
        }
        onRefresh()
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
    val connecting = connection is ConnectionState.Connecting ||
        connection is ConnectionState.Discovering ||
        connection is ConnectionState.Reconnecting

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                title = "Bike link",
                subtitle = "Manage the Bluetooth link, battery/signal mirroring and connection diagnostics.",
            )
        }

        bleState.error?.let { error ->
            item {
                AlertCard(
                    title = "Bike link error",
                    message = error,
                    dismissLabel = "Dismiss",
                    onDismiss = onClearError,
                )
            }
        }

        item {
            PulsarCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            bleStatusValue(connection),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            bleStatusDetail(bleState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = if (connection is ConnectionState.Connected) "Online" else "Offline",
                        tone = connectionTone(connection),
                    )
                }

                if (bleState.adapterState == AdapterState.OFF) {
                    Text(
                        "Bluetooth is turned off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    PillButton(
                        label = "Open Bluetooth settings",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        },
                        style = PillStyle.Tonal,
                    )
                }

                if (bleState.missingPermissions.isNotEmpty()) {
                    Text(
                        "Nearby-device access is required to scan and connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    PillButton(
                        label = "Grant Bluetooth access",
                        onClick = { request(PendingBleAction.SCAN) },
                    )
                }

                when (connection) {
                    is ConnectionState.Connected -> PillButton(
                        label = "Disconnect",
                        onClick = onDisconnect,
                        style = PillStyle.Outlined,
                    )
                    is ConnectionState.Failed -> PillButton(
                        label = "Retry",
                        onClick = onRetry,
                        icon = Icons.Outlined.Refresh,
                    )
                    is ConnectionState.MissingPermissions -> PillButton(
                        label = "Grant access and connect",
                        onClick = { request(PendingBleAction.CONNECT) },
                    )
                    is ConnectionState.BluetoothUnavailable -> PillButton(
                        label = "Retry",
                        onClick = onRetry,
                        icon = Icons.Outlined.Refresh,
                    )
                    else -> {
                        if (bleState.selected != null) {
                            PillButton(
                                label = "Connect",
                                onClick = ::connectOrRequest,
                                enabled = !bleState.scanning,
                            )
                        }
                    }
                }
            }
        }

        if (connection is ConnectionState.Connected) {
            item {
                PulsarCard {
                    PulsarSwitchRow(
                        title = "Battery & signal",
                        description = "Sends only the verified idle battery/signal frame. It never sends calls, contacts or messages.",
                        checked = bleState.phoneStatusEnabled,
                        onCheckedChange = onPhoneStatusChange,
                        enabled = bleState.phoneStatusAvailable,
                    )
                    when {
                        !bleState.phoneStatusAvailable -> Text(
                            "This bike did not expose the writable phone-status endpoint, so nothing will be sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        bleState.phoneStatusSending -> StatusPill(
                            text = "Sending battery and signal",
                            tone = StatusTone.Active,
                        )
                        bleState.phoneStatusError != null -> StatusPill(
                            text = "Mirroring stopped",
                            tone = StatusTone.Error,
                        )
                        bleState.phoneStatusEnabled -> StatusPill(
                            text = "Enabled · waiting for the bike",
                            tone = StatusTone.Warning,
                        )
                    }
                    if (bleState.phoneStatusAvailable) {
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
                            CardLink(
                                label = "Allow signal",
                                onClick = {
                                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                                },
                            )
                        }
                    }
                    bleState.phoneStatusError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        bleState.selected?.let { selected ->
            item {
                PulsarCard {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    Icons.Outlined.TwoWheeler,
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        }
                        StatusPill(
                            text = bleStatusValue(connection),
                            tone = connectionTone(connection),
                        )
                    }
                    PulsarDivider()
                    PulsarSwitchRow(
                        title = "Auto-connect",
                        description = "Reconnects while the app or background service is running.",
                        checked = bleState.autoConnect,
                        onCheckedChange = onAutoConnectChange,
                    )
                    PulsarDivider()
                    TextButton(onClick = onClearSelection) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Forget this bike",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Available nearby") }
        item {
            PulsarCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Pair another motorcycle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Turn the bike ignition and kill switch on, then scan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PillButton(
                    label = if (bleState.scanning) "Scanning…" else "Scan for bikes",
                    onClick = ::scanOrRequest,
                    enabled = !bleState.scanning && bleState.missingPermissions.isEmpty(),
                    icon = Icons.Outlined.Search,
                )
                if (bleState.devices.isEmpty()) {
                    Text(
                        if (bleState.scanning) "Listening for nearby Bluetooth devices…"
                        else "No devices found yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                DiagnosticsCard(connection, bleState.controlState)
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
    PulsarCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    device.name ?: "Unnamed Bluetooth device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    device.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(text = "${device.rssi} dBm", tone = StatusTone.Neutral)
                    if (device.bonded) StatusPill(text = "Paired", tone = StatusTone.Complete)
                }
            }
            PillButton(
                label = if (selected) "Selected" else "Use",
                onClick = onSelect,
                style = if (selected) PillStyle.Tonal else PillStyle.Outlined,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(
    connection: ConnectionState.Connected,
    controlState: ClusterControlState,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    PulsarCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Advanced diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${connection.services.size} GATT service${if (connection.services.size == 1) "" else "s"} · " +
                        "CONTROL polling ${if (controlState.polling) "on" else "off"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Hide diagnostics" else "Show diagnostics",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsarDivider()
                if (connection.services.isEmpty()) {
                    Text(
                        "The bike did not expose any GATT services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                connection.services.forEach { service -> DiagnosticsServiceCard(service) }
                controlState.frame?.let { frame ->
                    PulsarDivider()
                    Text(
                        "CONTROL counters",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricBlock("Volume", "${frame.volume}", Modifier.weight(1f))
                        MetricBlock("Accept", "${frame.callAccept}", Modifier.weight(1f))
                        MetricBlock("Reject", "${frame.callReject}", Modifier.weight(1f))
                        MetricBlock("Next", "${frame.next}", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricBlock("Previous", "${frame.previous}", Modifier.weight(1f))
                        MetricBlock(
                            "Play / pause",
                            "${if (frame.resume > 0) 1 else 0} / ${if (frame.pause > 0) 1 else 0}",
                            Modifier.weight(1f),
                        )
                        MetricBlock("Missed calls", "${frame.missedCallRead}", Modifier.weight(1f))
                        MetricBlock("Alerts", "${frame.alertRead}", Modifier.weight(1f))
                    }
                    Text(
                        "Read-only counters. This app performs no cluster actions. " +
                            "Response size: ${ControlProtocol.RESPONSE_SIZE} bytes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsServiceCard(service: BleService) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                service.uuid.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
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
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
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

private fun connectionTone(connection: ConnectionState): StatusTone = when (connection) {
    is ConnectionState.Connected -> StatusTone.Active
    is ConnectionState.Failed -> StatusTone.Error
    is ConnectionState.Connecting,
    is ConnectionState.Discovering,
    is ConnectionState.Reconnecting,
    -> StatusTone.Warning
    is ConnectionState.MissingPermissions,
    ConnectionState.BluetoothUnavailable,
    -> StatusTone.Warning
    ConnectionState.Idle -> StatusTone.Neutral
}
