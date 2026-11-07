package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BikeLinkState(
    val vin: String? = null,
    val selected: BikeTarget? = null,
    val autoConnect: Boolean = false,
    val connection: ConnectionState = ConnectionState.Idle,
    val settings: BikeLinkSettings = BikeLinkSettings(),
    val mirroringActive: Boolean = false,
    val mirroringError: String? = null,
)

/**
 * Owns the bike link outside any screen: connection, phone-status sending and CONTROL polling.
 *
 * The controller lives in the application scope so a foreground service can keep mirroring alive
 * after the activity is gone. It intentionally reads preferences on every reconcile instead of
 * keeping a second copy of the flags, so the service, settings screen and notification cannot
 * disagree.
 */
class BikeLinkController(
    private val devices: BikeDeviceStore,
    private val connection: BikeConnection,
    val preferences: BikeLinkPreferences,
    statuses: Flow<PhoneStatus>,
    private val scope: CoroutineScope,
) {
    private val sender = PhoneStatusSender(
        connection = connection,
        statuses = statuses,
        scope = scope,
        onStopped = ::onMirroringStopped,
        onSent = ::onMirroringSent,
    )
    private val controls = ControlPoller(connection, scope)

    private val _state = MutableStateFlow(BikeLinkState(settings = preferences.settings.value))
    val state: StateFlow<BikeLinkState> = _state.asStateFlow()

    /**
     * True while the app is in the background without background mode. The mirroring intent stays
     * armed, but nothing may reconnect until the rider returns to the app.
     */
    private var suspended = false

    /** Read-only cluster button counters, kept separate so the poll does not recompose the app. */
    val controlState: StateFlow<ClusterControlState> = controls.state

    init {
        scope.launch {
            preferences.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
                reconcile()
            }
        }
        scope.launch {
            connection.state.collect { current ->
                _state.update { it.copy(connection = current) }
                reconcile()
            }
        }
    }

    /** Called when a signed-in VIN becomes available. Changing VIN disarms mirroring. */
    fun onSession(vin: String) {
        suspended = false
        val previous = _state.value.vin
        if (previous == vin) {
            preferences.setArmedVin(vin)
            _state.update { it.copy(autoConnect = devices.autoConnect(vin)) }
            ensureConnectedIfWanted(retryFailure = true)
            return
        }
        stopRuntime()
        connection.stop()
        if (previous != null && preferences.settings.value.mirroring) {
            preferences.setMirroring(false)
        }
        preferences.setArmedVin(vin)
        _state.update {
            it.copy(
                vin = vin,
                selected = devices.selected(vin),
                autoConnect = devices.autoConnect(vin),
                mirroringError = null,
            )
        }
        ensureConnectedIfWanted(retryFailure = true)
    }

    /** Re-attaches to the armed VIN without a screen, e.g. after boot or a service restart. */
    fun ensureRunning() {
        val settings = preferences.settings.value
        if (!settings.mirroring) return
        suspended = false
        if (_state.value.vin == null) {
            val vin = settings.armedVin ?: return
            _state.update {
                it.copy(
                    vin = vin,
                    selected = devices.selected(vin),
                    autoConnect = devices.autoConnect(vin),
                )
            }
        }
        reconcile()
        ensureConnectedIfWanted(retryFailure = true)
    }

    fun onForeground() {
        suspended = false
        ensureConnectedIfWanted(retryFailure = true)
    }

    /**
     * Backgrounding only tears the link down when the rider has not opted into background mode.
     * The mirroring intent itself stays armed so returning to the app resumes it.
     */
    fun onBackground() {
        if (preferences.settings.value.runsInBackground) return
        suspended = true
        stopRuntime()
        connection.stop()
    }

    fun setMirroring(enabled: Boolean) {
        if (enabled) {
            _state.value.vin?.let { preferences.setArmedVin(it) }
        }
        preferences.setMirroring(enabled)
        if (!enabled) {
            stopRuntime()
            connection.setAutoReconnect(_state.value.autoConnect)
            _state.update { it.copy(mirroringError = null) }
        } else {
            connection.setAutoReconnect(true)
            ensureConnectedIfWanted(retryFailure = true)
        }
    }

    fun setKeepInBackground(enabled: Boolean) {
        preferences.setKeepInBackground(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        preferences.setStartOnBoot(enabled)
    }

    fun select(device: DiscoveredDevice) {
        val vin = _state.value.vin ?: return
        val target = BikeTarget(id = device.id, name = device.name, vin = vin)
        devices.select(target)
        _state.update { it.copy(selected = target, mirroringError = null) }
        connectTo(target)
    }

    fun connect() {
        connectTo(_state.value.selected ?: return)
    }

    fun retry() {
        connection.retry()
    }

    /** A manual disconnect also disarms mirroring; otherwise the link would immediately return. */
    fun disconnect() {
        if (preferences.settings.value.mirroring) preferences.setMirroring(false)
        stopRuntime()
        connection.stop()
        _state.update { it.copy(mirroringError = null) }
    }

    fun setAutoConnect(enabled: Boolean) {
        val vin = _state.value.vin ?: return
        devices.setAutoConnect(vin, enabled)
        _state.update { it.copy(autoConnect = enabled) }
        connection.setAutoReconnect(enabled || preferences.settings.value.mirroring)
        if (enabled) ensureConnectedIfWanted()
    }

    fun clearSelection() {
        val vin = _state.value.vin ?: return
        if (preferences.settings.value.mirroring) preferences.setMirroring(false)
        stopRuntime()
        connection.stop()
        devices.clear(vin)
        preferences.setArmedVin(null)
        _state.update { it.copy(selected = null, mirroringError = null) }
    }

    fun signOut() {
        preferences.setMirroring(false)
        preferences.setArmedVin(null)
        stopRuntime()
        connection.stop()
        _state.value = BikeLinkState(settings = preferences.settings.value)
    }

    private fun connectTo(target: BikeTarget) {
        connection.connect(
            target,
            autoReconnect = preferences.settings.value.mirroring || _state.value.autoConnect,
        )
    }

    /** Connects only when the rider wants a link, optionally reviving a terminal failure. */
    private fun ensureConnectedIfWanted(retryFailure: Boolean = false) {
        if (suspended) return
        val current = _state.value
        val target = current.selected ?: return
        if (!preferences.settings.value.mirroring && !current.autoConnect) return
        when (connection.state.value) {
            is ConnectionState.Failed -> if (retryFailure) connectTo(target)
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Discovering,
            is ConnectionState.Reconnecting,
            -> return
            else -> connectTo(target)
        }
    }

    private fun reconcile() {
        val settings = preferences.settings.value
        val connected = connection.state.value as? ConnectionState.Connected

        val canMirror = connected != null && settings.mirroring &&
            PhoneStatusProtocol.isSupportedBy(connected.services)
        if (canMirror) {
            // Starting the sender only means it may try; mirroring is not reported as active
            // until the sender confirms a real GATT write.
            sender.start()
        } else {
            sender.stop()
        }

        // CONTROL reads share the transport with the status writes. Polling before the first
        // confirmed write delays the readiness probe/MTU exchange and can make the cluster
        // ignore the first ATT operation, so wait for a successful status write.
        val canPoll = connected != null && canMirror && sender.hasSent &&
            ControlProtocol.isSupportedBy(connected.services)
        if (canPoll) {
            controls.start()
        } else {
            controls.stop()
        }

        _state.update {
            it.copy(
                mirroringActive = sender.running && sender.hasSent && it.mirroringError == null,
                mirroringError = if (settings.mirroring) it.mirroringError else null,
            )
        }

        if (settings.mirroring) ensureConnectedIfWanted()
    }

    /** A confirmed first status write: mirroring is finally real and diagnostics may poll. */
    private fun onMirroringSent() {
        _state.update { it.copy(mirroringError = null) }
        reconcile()
    }

    private fun stopRuntime() {
        sender.stop()
        controls.stop()
        _state.update { it.copy(mirroringActive = false) }
    }

    private fun onMirroringStopped(error: Throwable?) {
        controls.stop()
        val connected = connection.state.value as? ConnectionState.Connected
        val stoppedUnexpectedly = error == null &&
            preferences.settings.value.mirroring &&
            connected != null &&
            PhoneStatusProtocol.isSupportedBy(connected.services)
        _state.update {
            it.copy(
                mirroringActive = false,
                mirroringError = when {
                    error != null -> MIRRORING_STOPPED_MESSAGE
                    stoppedUnexpectedly -> MIRRORING_STOPPED_MESSAGE
                    else -> null
                },
            )
        }
    }

    companion object {
        const val MIRRORING_STOPPED_MESSAGE: String =
            "Phone status mirroring stopped. Reconnect and try again."
    }
}
