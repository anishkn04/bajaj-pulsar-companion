package dev.pulsarconnect.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState()
            }
            is UiState.SignedOut -> LoginScreen(
                error = current.error,
                busy = current.busy,
                onSignIn = viewModel::signIn,
            )
            is UiState.SignedIn -> SignedInScreen(current, viewModel, bleViewModel)
        }
    }
}

private enum class NavDestination { HOME, BIKE, TRIPS, REMINDERS, SETTINGS }

private data class NavItem(
    val destination: NavDestination,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem(NavDestination.HOME, "Home", Icons.Outlined.Home),
    NavItem(NavDestination.BIKE, "Bike link", Icons.Outlined.Bluetooth),
    NavItem(NavDestination.TRIPS, "Trips", Icons.AutoMirrored.Outlined.List),
    NavItem(NavDestination.REMINDERS, "Reminders", Icons.Outlined.Notifications),
    NavItem(NavDestination.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
private fun SignedInScreen(
    state: UiState.SignedIn,
    viewModel: AppViewModel,
    bleViewModel: BleViewModel,
) {
    val bleState by bleViewModel.state.collectAsStateWithLifecycle()
    var navDestination by rememberSaveable(state.vin) { mutableStateOf(NavDestination.HOME) }
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

    BackHandler(enabled = navDestination != NavDestination.HOME) {
        navDestination = NavDestination.HOME
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            PulsarNavigationBar(
                selected = navDestination,
                onSelected = { navDestination = it },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            when (navDestination) {
                NavDestination.TRIPS -> TripHistoryScreen(
                    state = state,
                    onStartTrip = viewModel::startTrip,
                    onEndTrip = viewModel::endTrip,
                    onLoadMore = viewModel::loadMoreTrips,
                    onClearError = viewModel::clearError,
                    onRetry = viewModel::retryLocalRecords,
                )
                NavDestination.REMINDERS -> RemindersScreen(
                    state = state,
                    onAddReminder = viewModel::addReminder,
                    onCompleteReminder = viewModel::completeReminder,
                    onClearError = viewModel::clearError,
                    onRetry = viewModel::retryLocalRecords,
                    onNotificationsEnabledChange = viewModel::setReminderNotificationsEnabled,
                    onNotificationsRefresh = viewModel::refreshReminderNotifications,
                )
                NavDestination.BIKE -> BikeConnectionScreen(
                    bleState = bleState,
                    onScan = bleViewModel::scan,
                    onSelect = bleViewModel::select,
                    onConnect = bleViewModel::connect,
                    onDisconnect = bleViewModel::disconnect,
                    onRetry = bleViewModel::retry,
                    onAutoConnectChange = bleViewModel::setAutoConnect,
                    onClearSelection = bleViewModel::clearSelection,
                    onPhoneStatusChange = bleViewModel::setMirroring,
                    onRefresh = bleViewModel::onPermissionsChanged,
                    onClearError = bleViewModel::clearError,
                )
                NavDestination.SETTINGS -> SettingsScreen(
                    bleState = bleState,
                    onMirroringChange = bleViewModel::setMirroring,
                    onBackgroundChange = bleViewModel::setKeepInBackground,
                    onBootChange = bleViewModel::setStartOnBoot,
                    onStopMirroring = { bleViewModel.disconnect(); navDestination = NavDestination.HOME },
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
                )
                else -> HomeScreen(
                    state = state,
                    bleState = bleState,
                    onSignOut = viewModel::signOut,
                    onTrips = { navDestination = NavDestination.TRIPS },
                    onReminders = { navDestination = NavDestination.REMINDERS },
                    onBike = { navDestination = NavDestination.BIKE },
                    onClearError = viewModel::clearError,
                    onRetry = viewModel::retryLocalRecords,
                )
            }
        }
    }
}

@Composable
private fun PulsarNavigationBar(
    selected: NavDestination,
    onSelected: (NavDestination) -> Unit,
) {
    Column {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
        ) {
            navItems.forEach { item ->
                val isSelected = selected == item.destination
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelected(item.destination) },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                        )
                    },
                    label = {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
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
        val mirroring = when {
            state.phoneStatusError != null -> " Battery & signal mirroring is stopped."
            state.phoneStatusSending -> " Battery & signal mirroring is active."
            state.phoneStatusEnabled -> " Battery & signal mirroring is enabled but not sending."
            else -> ""
        }
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

internal fun reminderDueCount(state: UiState.SignedIn, now: Long): Int =
    state.reminders.count { it.isDue(state.lastOdometer ?: Double.NaN, now) }
