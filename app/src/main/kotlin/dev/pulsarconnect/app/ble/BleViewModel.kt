package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeDeviceStore
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeScanner
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.BleTransport
import dev.pulsarconnect.core.ble.ClusterControlState
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.DiscoveredDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModel

data class BleUiState(
    val vin: String? = null,
    val selected: BikeTarget? = null,
    val autoConnect: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val connection: ConnectionState = ConnectionState.Idle,
    val adapterState: AdapterState = AdapterState.UNKNOWN,
    val missingPermissions: Set<BlePermission> = emptySet(),
    val error: String? = null,
    val phoneStatusEnabled: Boolean = false,
    val phoneStatusAvailable: Boolean = false,
    val phoneStatusSending: Boolean = false,
    val phoneStatusError: String? = null,
    val callerNameEnabled: Boolean = false,
    val missedCallEnabled: Boolean = false,
    val callerNameAvailable: Boolean = false,
    val missedCallAvailable: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val settings: BikeLinkSettings = BikeLinkSettings(),
    val controlState: ClusterControlState = ClusterControlState(),
)

/** Hook for the app layer to start/stop the foreground service when mirroring changes. */
interface BikeLinkRuntime {
    fun setEnabled(enabled: Boolean)
}

/**
 * App-scoped bridge between the UI and the [BikeLinkController].
 *
 * The controller (and therefore the connection, sender and CONTROL poller) lives in the
 * application scope so a foreground service can keep mirroring alive after the activity is
 * gone. This view model only reflects state and routes user actions to the controller and the
 * runtime.
 */
class BleViewModel(
    application: android.app.Application,
    private val devices: BikeDeviceStore,
    private val scanner: BikeScanner,
    private val transport: BleTransport,
    private val controller: BikeLinkController,
    private val runtime: BikeLinkRuntime,
) : ViewModel() {

    private val appContext = application.applicationContext

    private val _state = MutableStateFlow(BleUiState(
        adapterState = transport.adapterState.value,
        missingPermissions = transport.missingPermissions(),
        notificationAccessGranted = NotificationAccess.isGranted(appContext),
    ))
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    /** Cluster CONTROL button counters, read-only. May be null when unsupported or not polling. */
    val controlState: StateFlow<ClusterControlState> = controller.controlState

    val settings: BikeLinkSettings
        get() = controller.state.value.settings

    private var scanGeneration = 0L
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    init {
        scope.launch {
            transport.adapterState.collect { adapter ->
                _state.update { it.copy(adapterState = adapter) }
            }
        }
        scope.launch {
            controller.state.collect { link ->
                val connected = link.connection as? ConnectionState.Connected
                val available = connected != null &&
                    dev.pulsarconnect.core.ble.PhoneStatusProtocol.isSupportedBy(connected.services)
                _state.update { current ->
                    current.copy(
                        vin = link.vin,
                        selected = link.selected,
                        autoConnect = link.autoConnect,
                        connection = link.connection,
                        settings = link.settings,
                        devices = if (current.vin != link.vin) emptyList() else current.devices,
                        phoneStatusEnabled = link.settings.mirroring,
                        phoneStatusAvailable = available,
                        phoneStatusSending = link.mirroringActive,
                        phoneStatusError = link.mirroringError,
                        callerNameEnabled = link.settings.mirrorCallerName,
                        missedCallEnabled = link.settings.mirrorMissedCalls,
                        callerNameAvailable = link.callerMirroringAvailable,
                        missedCallAvailable = link.missedCallMirroringAvailable,
                    )
                }
            }
        }
        scope.launch {
            controller.controlState.collect { control ->
                _state.update { it.copy(controlState = control) }
            }
        }
        // Keep the foreground service alive whenever background mirroring is armed. This is
        // intentionally based on the rider's intent, not on an active sender: the service must
        // already be alive while the bike is reconnecting.
        scope.launch {
            var serviceEnabled: Boolean? = null
            controller.state.collect { link ->
                val wanted = link.settings.runsInBackground &&
                    link.settings.armedVin != null &&
                    link.selected != null
                if (wanted != serviceEnabled) {
                    serviceEnabled = wanted
                    runtime.setEnabled(wanted)
                }
            }
        }

        transport.refreshAdapterState()
    }

    /** Re-attaches to the armed VIN after boot or a process restart. */
    fun ensureRunning() {
        controller.ensureRunning()
    }

    /** Called when a signed-in VIN becomes available. Changing VIN disarms mirroring. */
    fun onSession(vin: String) {
        controller.onSession(vin)
    }

    /** Foreground: resume the link when mirroring or auto-connect wants it. */
    fun onForeground() {
        refreshNotificationAccess()
        controller.onForeground()
    }

    /** Background: keep the link when background mode is on, otherwise stop it. */
    fun onBackground() {
        controller.onBackground()
    }

    fun refreshPlatformState() {
        transport.refreshAdapterState()
        _state.update {
            it.copy(
                missingPermissions = transport.missingPermissions(),
                adapterState = transport.adapterState.value,
            )
        }
    }

    /** Called after a permission dialog: refresh state and resume a link that was waiting. */
    fun onPermissionsChanged() {
        refreshPlatformState()
        refreshNotificationAccess()
        controller.onForeground()
    }

    /**
     * Re-reads the special notification-access grant. The system settings screen is outside the
     * normal permission flow, so the UI calls this when it resumes.
     */
    fun refreshNotificationAccess() {
        val granted = NotificationAccess.isGranted(appContext)
        _state.update {
            if (it.notificationAccessGranted == granted) it
            else it.copy(notificationAccessGranted = granted)
        }
    }

    fun scan() {
        val current = controller.state.value
        if (current.vin == null || _state.value.scanning) return
        refreshPlatformState()
        val generation = ++scanGeneration
        _state.update { it.copy(scanning = true, error = null, devices = emptyList()) }
        scope.launch {
            try {
                scanner.scan().collect { found ->
                    if (generation == scanGeneration) {
                        _state.update { it.copy(devices = found) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (generation == scanGeneration) {
                    _state.update {
                        it.copy(error = error.message ?: "Could not scan for nearby Bluetooth devices.")
                    }
                }
            } finally {
                if (generation == scanGeneration) {
                    _state.update { it.copy(scanning = false) }
                }
            }
        }
    }

    fun select(device: DiscoveredDevice) {
        val vin = controller.state.value.vin ?: return
        val target = BikeTarget(id = device.id, name = device.name, vin = vin)
        devices.select(target)
        _state.update { it.copy(selected = target, error = null) }
        controller.select(device)
    }

    fun connect() {
        controller.connect()
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun retry() {
        controller.retry()
    }

    fun setAutoConnect(enabled: Boolean) {
        controller.setAutoConnect(enabled)
    }

    fun clearSelection() {
        controller.clearSelection()
    }

    fun setMirroring(enabled: Boolean) {
        controller.setMirroring(enabled)
    }

    fun setKeepInBackground(enabled: Boolean) {
        controller.setKeepInBackground(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        controller.setStartOnBoot(enabled)
    }

    /** Opt-in caller-name mirroring; defaults off because it carries a contact name. */
    fun setMirrorCallerName(enabled: Boolean) {
        controller.setMirrorCallerName(enabled)
    }

    /** Opt-in missed-call icon; the frame carries no personal fields. */
    fun setMirrorMissedCalls(enabled: Boolean) {
        controller.setMirrorMissedCalls(enabled)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun signOut() {
        controller.signOut()
        _state.value = BleUiState()
    }

    /** Cancels the view model's coroutines. For use in tests. */
    fun cancel() {
        job.cancel()
    }

    override fun onCleared() {
        // The controller outlives this view model: do not stop it here.
        job.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            application: android.app.Application,
            devices: BikeDeviceStore,
            scanner: BikeScanner,
            transport: BleTransport,
            controller: BikeLinkController,
            runtime: BikeLinkRuntime,
        ): androidx.lifecycle.ViewModelProvider.Factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                BleViewModel(
                    application = application,
                    devices = devices,
                    scanner = scanner,
                    transport = transport,
                    controller = controller,
                    runtime = runtime,
                ) as T
        }
    }
}
