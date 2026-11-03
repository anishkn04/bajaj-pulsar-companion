package dev.pulsarconnect.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.app.ble.BleViewModel
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.data.isDue

@Composable
fun PulsarConnectApp(viewModel: AppViewModel, bleViewModel: BleViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state !is UiState.SignedIn) bleViewModel.signOut()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val current = state) {
            UiState.Loading -> LoadingScreen()
            is UiState.SignedOut -> LoginScreen(
                error = current.error,
                busy = current.busy,
                onSignIn = viewModel::signIn,
            )
            is UiState.SignedIn -> SignedInScreen(current, viewModel, bleViewModel)
        }
    }
}

@Composable
private fun SignedInScreen(
    state: UiState.SignedIn,
    viewModel: AppViewModel,
    bleViewModel: BleViewModel,
) {
    val bleState by bleViewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(state.vin) { mutableStateOf("home") }
    val snackbarHostState = remember(state.vin) { SnackbarHostState() }
    val latestState by rememberUpdatedState(state)
    val context = LocalContext.current

    LaunchedEffect(state.vin) { bleViewModel.onSession(state.vin) }
    LaunchedEffect(state.vin) { bleViewModel.ensureRunning() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bleViewModel.onForeground()
                Lifecycle.Event.ON_STOP -> bleViewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.vin, state.error, state.localLoadFailed) {
        state.error?.let { error ->
            val result = snackbarHostState.showSnackbar(
                message = error,
                actionLabel = if (state.localLoadFailed) "Retry" else "Dismiss",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (latestState.localLoadFailed) {
                    if (!latestState.busy && !latestState.localLoading) {
                        viewModel.retryLocalRecords()
                    }
                } else {
                    viewModel.clearError()
                }
            }
        }
    }
    BackHandler(enabled = tab != "home") { tab = "home" }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.imePadding()) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            when (tab) {
                "trips" -> TripHistoryScreen(
                    state = state,
                    onBack = { tab = "home" },
                    onStartTrip = viewModel::startTrip,
                    onEndTrip = viewModel::endTrip,
                    onLoadMore = viewModel::loadMoreTrips,
                    onClearError = viewModel::clearError,
                    onRetry = viewModel::retryLocalRecords,
                )
                "reminders" -> RemindersScreen(
                    state = state,
                    onBack = { tab = "home" },
                    onAddReminder = viewModel::addReminder,
                    onCompleteReminder = viewModel::completeReminder,
                    onClearError = viewModel::clearError,
                    onRetry = viewModel::retryLocalRecords,
                    onNotificationsEnabledChange = viewModel::setReminderNotificationsEnabled,
                    onNotificationsRefresh = viewModel::refreshReminderNotifications,
                )
                "bike" -> BikeConnectionScreen(
                    bleState = bleState,
                    onScan = bleViewModel::scan,
                    onSelect = bleViewModel::select,
                    onConnect = bleViewModel::connect,
                    onDisconnect = bleViewModel::disconnect,
                    onRetry = bleViewModel::retry,
                    onAutoConnectChange = bleViewModel::setAutoConnect,
                    onClearSelection = bleViewModel::clearSelection,
                    onPhoneStatusChange = bleViewModel::setMirroring,
                    onRefresh = bleViewModel::refreshPlatformState,
                    onClearError = bleViewModel::clearError,
                    onBack = { tab = "home" },
                )
                "settings" -> SettingsScreen(
                    state = state,
                    bleState = bleState,
                    onMirroringChange = bleViewModel::setMirroring,
                    onBackgroundChange = bleViewModel::setKeepInBackground,
                    onBootChange = bleViewModel::setStartOnBoot,
                    onStopMirroring = { bleViewModel.disconnect(); tab = "home" },
                    onOpenBatteryOptimization = {
                        val intent = Intent().apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                            } else {
                                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                data = Uri.parse("package:${context.packageName}")
                            }
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    },
                    onBack = { tab = "home" },
                )
                else -> HomeScreen(
                    state = state,
                    bleState = bleState,
                    onSignOut = viewModel::signOut,
                    onTrips = { tab = "trips" },
                    onReminders = { tab = "reminders" },
                    onBike = { tab = "bike" },
                    onSettings = { tab = "settings" },
                    onClearError = viewModel::clearError,
                    onRetry = viewModel::retryLocalRecords,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: UiState.SignedIn,
    bleState: BleUiState,
    onMirroringChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    onBootChange: (Boolean) -> Unit,
    onStopMirroring: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = bleState.controlState // not used directly; kept for future diagnostics
    val notificationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    val backgroundOn = bleState.connection is dev.pulsarconnect.core.ble.ConnectionState.Connected ||
        state.vin != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to home") }
        Text("Settings", style = MaterialTheme.typography.headlineLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Bike link mirroring",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Mirror phone status to cluster", fontWeight = FontWeight.Medium)
                        Text(
                            "Sends only the verified idle battery/signal frame. " +
                                "Never calls, contacts or messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = bleState.settings.mirroring,
                        onCheckedChange = onMirroringChange,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Keep mirroring in background", fontWeight = FontWeight.Medium)
                        Text(
                            "Uses a foreground service so mirroring survives " +
                                "leaving the app. Stops when you turn mirroring off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = bleState.settings.keepInBackground,
                        onCheckedChange = onBackgroundChange,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Resume mirroring after reboot", fontWeight = FontWeight.Medium)
                        Text(
                            "Only active when mirroring and background mode are both on " +
                                "and a bike is selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = bleState.settings.startOnBoot,
                        onCheckedChange = onBootChange,
                    )
                }
                if (bleState.settings.keepInBackground && !notificationGranted) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Notification permission is off",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "The foreground service still runs, but you won't see its " +
                                    "notification. Grant it to keep the stop control visible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Button(onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(
                                        Settings.EXTRA_APP_PACKAGE,
                                        context.packageName,
                                    )
                                }
                                if (intent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(intent)
                                }
                            }) { Text("Grant notification permission") }
                        }
                    }
                }
                if (bleState.settings.mirroring) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = onStopMirroring) {
                            Text("Stop mirroring now")
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Battery optimization",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "If mirroring stops shortly after you leave the app, add Pulsar Connect to " +
                        "the battery-optimization exempt list so the foreground service is not killed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenBatteryOptimization) {
                    Text("Open battery settings")
                }
            }
        }

        Text(
            "Mirroring, background mode and boot resume are global preferences. " +
                "They are not vehicle-specific and never store credentials, tokens or captures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LocalDataStatus(
    state: UiState.SignedIn,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Saved on this phone for this VIN. Available offline; no cloud sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.localLoading || state.busy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(if (state.localLoading) "Loading saved records…" else "Saving…")
            }
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
            if (!state.localLoadFailed) {
                TextButton(onClick = onClearError, enabled = !state.busy) { Text("Dismiss error") }
            }
        }
        if (state.localLoadFailed) {
            Text(
                "Saved records could not be loaded. Trips and reminders are read-only until loading succeeds.",
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, enabled = !state.busy && !state.localLoading) {
                Text("Retry loading records")
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "Pulsar Connect",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoginScreen(
    error: String?,
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
) {
    var vin by rememberSaveable { mutableStateOf("") }
    var accessCode by rememberSaveable { mutableStateOf("") }
    var revealCode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "PULSAR CONNECT",
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 4.sp),
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = "Your bike.\nYour data.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 44.sp,
        )

        Text(
            text = "Log in to keep a manual trip log and service reminders on this phone. " +
                "Saved records are available offline; Bluetooth is only used for the bike link.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.uppercase() },
            label = { Text("Vehicle identification number") },
            placeholder = { Text("17 characters") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
        )

        OutlinedTextField(
            value = accessCode,
            onValueChange = { accessCode = it.trim() },
            label = { Text("Access code") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (revealCode) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!busy && vin.isNotBlank() && accessCode.isNotBlank()) {
                        onSignIn(vin, accessCode)
                    }
                },
            ),
            trailingIcon = {
                TextButton(onClick = { revealCode = !revealCode }) {
                    Text(if (revealCode) "Hide" else "Show")
                }
            },
        )

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = { onSignIn(vin, accessCode) },
            enabled = !busy && vin.isNotBlank() && accessCode.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Log in", style = MaterialTheme.typography.titleMedium)
            }
        }

        Text(
            text = "Stored encrypted on this device and sent only to Bajaj to sign in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeScreen(
    state: UiState.SignedIn,
    bleState: BleUiState,
    onSignOut: () -> Unit,
    onTrips: () -> Unit,
    onReminders: () -> Unit,
    onBike: () -> Unit,
    onSettings: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "PULSAR CONNECT",
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 4.sp),
            color = MaterialTheme.colorScheme.primary,
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = state.bikeName ?: "Your Pulsar",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.vin,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LocalDataStatus(state, onClearError, onRetry)
        StatusCard(
            title = "Bike link",
            value = bleStatusValue(bleState.connection),
            detail = bleStatusDetail(bleState),
            actionLabel = "Open bike link",
            onAction = onBike,
        )
        StatusCard(
            title = "Trips",
            value = "${state.trips.size}${if (state.hasMoreTrips) "+" else ""} saved trips",
            detail = if (state.activeTrip != null) "A manual trip is in progress."
                else "Manually record start and end odometer readings. Local only; live cluster data is not decoded yet.",
            actionLabel = "View trips",
            onAction = onTrips,
        )
        val now = rememberCurrentTimeMillis()
        val pending = state.reminders.count { !it.completed }
        val due = state.reminders.count { it.isDue(state.lastOdometer ?: Double.NaN, now) }
        StatusCard(
            title = "Reminders",
            value = "$pending pending · $due due",
            detail = "Due status uses dates and your latest recorded odometer, not live bike data.",
            actionLabel = "Manage reminders",
            onAction = onReminders,
        )
        StatusCard(
            title = "Settings",
            value = "Mirroring · background · boot",
            detail = "Keep the bike link alive when the app is backgrounded and resume it after reboot.",
            actionLabel = "Open settings",
            onAction = onSettings,
        )

        Text(
            "Signing out stops reminder alerts but keeps your alert preference and local trip and reminder data on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSignOut, enabled = !state.busy) {
                Text("Sign out", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

internal fun bleStatusValue(connection: ConnectionState): String = when (connection) {
    ConnectionState.Idle -> "Not connected"
    ConnectionState.BluetoothUnavailable -> "Bluetooth off"
    is ConnectionState.MissingPermissions -> "Permission needed"
    is ConnectionState.Connecting, is ConnectionState.Discovering -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting…"
    is ConnectionState.Failed -> "Connection failed"
}

internal fun bleStatusDetail(state: BleUiState): String = when (val connection = state.connection) {
    is ConnectionState.Connected -> connection.services.size.let { count ->
        val target = connection.target.name ?: "Your bike"
        val mirroring = if (state.phoneStatusEnabled) " Battery & signal mirroring is on." else ""
        "$target · $count GATT service${if (count == 1) "" else "s"} discovered.$mirroring"
    }
    is ConnectionState.Failed -> connection.reason.toString()
    ConnectionState.BluetoothUnavailable -> "Turn on Bluetooth, then open the bike link."
    is ConnectionState.MissingPermissions -> "Grant nearby-device access to scan for your bike."
    else -> if (state.selected != null) {
        val name = state.selected.name ?: "Selected bike"
        if (state.autoConnect) "$name · auto-connect is on." else "$name · auto-connect is off."
    } else {
        "Scan for your bike and keep its link across launches. No cluster data is decoded yet."
    }
}
