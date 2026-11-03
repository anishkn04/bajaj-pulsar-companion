package dev.pulsarconnect.app.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pulsarconnect.app.AppContainer
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeDeviceStore
import dev.pulsarconnect.core.ble.BikeScanner
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.BleScanner
import dev.pulsarconnect.core.ble.BleTransport
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.DiscoveredDevice
import dev.pulsarconnect.core.ble.PhoneStatusProtocol
import dev.pulsarconnect.core.ble.PhoneStatusSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val phoneStatusAvailable: Boolean = false,
    val phoneStatusEnabled: Boolean = false,
    val phoneStatusSending: Boolean = false,
    val phoneStatusError: String? = null,
)

class BleViewModel(
    private val devices: BikeDeviceStore,
    private val scanner: BleScanner,
    private val connection: BikeConnection,
    private val transport: BleTransport,
    private val phoneStatusSource: PhoneStatusSource = PhoneStatusSource { emptyFlow() },
) : ViewModel() {

    private val phoneStatusSender = PhoneStatusSender(
        connection = connection,
        statuses = phoneStatusSource.statuses(),
        scope = viewModelScope,
        onStopped = ::onPhoneStatusStopped,
    )

    private val _state = MutableStateFlow(
        BleUiState(
            adapterState = transport.adapterState.value,
            missingPermissions = transport.missingPermissions(),
        ),
    )
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var scanGeneration = 0L

    init {
        viewModelScope.launch {
            connection.state.collect { current ->
                val available = current is ConnectionState.Connected &&
                    PhoneStatusProtocol.isSupportedBy(current.services)
                _state.update { it.copy(connection = current, phoneStatusAvailable = available) }
                if (current !is ConnectionState.Connected) stopPhoneStatus()
            }
        }
        viewModelScope.launch {
            transport.adapterState.collect { adapter ->
                _state.update { it.copy(adapterState = adapter) }
            }
        }
    }

    /** Called when a session becomes available. Safe to call again after recreation. */
    fun onSession(vin: String) {
        val changed = _state.value.vin != vin
        if (changed) {
            stopScan()
            stopPhoneStatus()
            connection.stop()
            _state.value = BleUiState()
        }
        transport.refreshAdapterState()
        _state.update {
            it.copy(
                vin = vin,
                selected = devices.selected(vin),
                autoConnect = devices.autoConnect(vin),
                missingPermissions = transport.missingPermissions(),
                adapterState = transport.adapterState.value,
            )
        }
        if (changed) {
            val selected = devices.selected(vin)
            if (selected != null && devices.autoConnect(vin)) {
                connection.connect(selected)
            }
        }
    }

    /**
     * Auto-connect is foreground-only: there is no background service and background
     * collection stops the platform scan and connection.
     */
    fun onForeground() {
        val current = _state.value
        transport.refreshAdapterState()
        _state.update {
            it.copy(
                missingPermissions = transport.missingPermissions(),
                adapterState = transport.adapterState.value,
            )
        }
        val vin = current.vin ?: return
        val selected = devices.selected(vin) ?: return
        if (!devices.autoConnect(vin)) return
        val connectionState = connection.state.value
        if (connectionState is ConnectionState.Idle ||
            connectionState is ConnectionState.Failed ||
            connectionState is ConnectionState.MissingPermissions ||
            connectionState is ConnectionState.BluetoothUnavailable
        ) {
            connection.connect(selected)
        }
    }

    fun onBackground() {
        stopScan()
        stopPhoneStatus()
        connection.stop()
        _state.update { it.copy(scanning = false) }
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

    fun scan() {
        if (_state.value.vin == null || _state.value.scanning) return
        refreshPlatformState()
        stopScan()
        val generation = scanGeneration
        _state.update { it.copy(scanning = true, error = null, devices = emptyList()) }
        scanJob = viewModelScope.launch {
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
        val vin = _state.value.vin ?: return
        val target = BikeTarget(id = device.id, name = device.name, vin = vin)
        stopPhoneStatus(clearError = true)
        devices.select(target)
        _state.update { it.copy(selected = target, error = null) }
        connection.connect(target, autoReconnect = devices.autoConnect(vin))
    }

    fun connect() {
        val vin = _state.value.vin ?: return
        val selected = devices.selected(vin) ?: run {
            _state.update { it.copy(error = "Choose your bike from the scan results first.") }
            return
        }
        connection.connect(selected, autoReconnect = devices.autoConnect(vin))
    }

    fun disconnect() {
        stopPhoneStatus(clearError = true)
        connection.stop()
    }

    fun retry() {
        connection.retry()
    }

    fun setAutoConnect(enabled: Boolean) {
        val vin = _state.value.vin ?: return
        devices.setAutoConnect(vin, enabled)
        _state.update { it.copy(autoConnect = enabled) }
        connection.setAutoReconnect(enabled)
    }

    fun clearSelection() {
        val vin = _state.value.vin ?: return
        stopPhoneStatus(clearError = true)
        connection.stop()
        devices.clear(vin)
        _state.update { it.copy(selected = null, devices = emptyList()) }
    }

    /**
     * Starts or stops the opt-in battery/signal mirror. Enabling requires a connected peer
     * exposing the writable GENERAL endpoint; the sender stops on any disconnect.
     */
    fun setPhoneStatusEnabled(enabled: Boolean) {
        if (!enabled) {
            stopPhoneStatus(clearError = true)
            return
        }
        val current = _state.value
        if (!current.phoneStatusAvailable) {
            _state.update { it.copy(phoneStatusError = "Connect a compatible bike first.") }
            return
        }
        if (current.phoneStatusEnabled && phoneStatusSender.running) return
        _state.update {
            it.copy(
                phoneStatusEnabled = true,
                phoneStatusSending = true,
                phoneStatusError = null,
            )
        }
        phoneStatusSender.start()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun signOut() {
        stopScan()
        stopPhoneStatus()
        connection.stop()
        _state.value = BleUiState()
    }

    override fun onCleared() {
        phoneStatusSender.stop()
        super.onCleared()
    }

    private fun onPhoneStatusStopped(error: Throwable?) {
        _state.update {
            it.copy(
                phoneStatusEnabled = false,
                phoneStatusSending = false,
                phoneStatusError = if (error != null) {
                    "Phone status mirroring stopped. Reconnect and try again."
                } else {
                    it.phoneStatusError
                },
            )
        }
    }

    private fun stopPhoneStatus(clearError: Boolean = false) {
        phoneStatusSender.stop()
        _state.update {
            it.copy(
                phoneStatusEnabled = false,
                phoneStatusSending = false,
                phoneStatusError = if (clearError) null else it.phoneStatusError,
            )
        }
    }

    private fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BleViewModel(
                devices = container.bikeDevices,
                scanner = BikeScanner(container.bleScanSource),
                connection = container.bikeConnection,
                transport = container.bleTransport,
                phoneStatusSource = container.phoneStatusSource,
            ) as T
        }
    }
}
