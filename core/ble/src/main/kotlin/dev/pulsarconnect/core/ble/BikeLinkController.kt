package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    val callerMirroringAvailable: Boolean = false,
    val missedCallMirroringAvailable: Boolean = false,
    val passiveWait: PassiveWaitState = PassiveWaitState.OFF,
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
    callEvents: Flow<CallEvent> = emptyFlow(),
) {
    private val sender = PhoneStatusSender(
        connection = connection,
        statuses = statuses,
        scope = scope,
        callEvents = callEvents,
        callOptions = {
            val settings = preferences.settings.value
            CallMirrorOptions(
                callerName = settings.mirrorCallerName,
                missedCalls = settings.mirrorMissedCalls,
            )
        },
        onStopped = ::onMirroringStopped,
        onSent = ::onMirroringSent,
    )
    private val controls = ControlPoller(connection, scope)

    private val _state = MutableStateFlow(BikeLinkState(settings = preferences.settings.value))
    val state: StateFlow<BikeLinkState> = _state.asStateFlow()

    /** Recent in-memory link events for the diagnostics card and bug-report export. */
    val linkLog = LinkLog()
    val logEntries: StateFlow<List<LinkLogEntry>> = linkLog.entries

    /** True while Android has been asked to hold a passive connection. */
    private var passiveWaitArmed = false

    /** True once a passive wait was the reason a connection attached. */
    private var passiveAttached = false

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
                logSettingChanges(_state.value.settings, settings)
                _state.update { it.copy(settings = settings) }
                reconcile()
            }
        }
        scope.launch {
            var previous: ConnectionState? = null
            connection.state.collect { current ->
                if (current != previous) {
                    logTransition(previous, current)
                    previous = current
                }
                _state.update {
                    it.copy(connection = current, passiveWait = passiveWaitState())
                }
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
        setPassiveWait(shouldWaitForBike())
        ensureConnectedIfWanted(retryFailure = true)
    }

    /**
     * Backgrounding only tears the link down when the rider has not opted into background mode.
     * The mirroring intent itself stays armed so returning to the app resumes it.
     */
    fun onBackground() {
        if (preferences.settings.value.runsInBackground) {
            // Keep the link armed and let Android attach passively when the bike powers on.
            setPassiveWait(true)
            return
        }
        suspended = true
        setPassiveWait(false)
        linkLog.record(
            LinkLogKind.SETTING,
            "Background mirroring is off; the link is released while the app is in the background",
        )
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
            setPassiveWait(false)
            connection.setAutoReconnect(_state.value.autoConnect)
            _state.update { it.copy(mirroringError = null) }
        } else {
            connection.setAutoReconnect(true)
            setPassiveWait(shouldWaitForBike())
            ensureConnectedIfWanted(retryFailure = true)
        }
    }

    fun setKeepInBackground(enabled: Boolean) {
        preferences.setKeepInBackground(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        preferences.setStartOnBoot(enabled)
    }

    /** Opt-in caller-name mirroring. Turning it off stops any caller text on the next frame. */
    fun setMirrorCallerName(enabled: Boolean) {
        preferences.setMirrorCallerName(enabled)
    }

    /** Opt-in missed-call icon. The frame carries no name or number. */
    fun setMirrorMissedCalls(enabled: Boolean) {
        preferences.setMirrorMissedCalls(enabled)
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
        val waitForBike = shouldWaitForBike()
        setPassiveWaitIntent(waitForBike)
        connection.connect(
            target,
            autoReconnect = preferences.settings.value.mirroring || _state.value.autoConnect,
            waitForBike = waitForBike,
        )
    }

    /**
     * True while Android may hold a passive connection: the rider armed background mirroring
     * and the app is not suspended. Keeps the link armed across bike power cycles.
     */
    private fun shouldWaitForBike(): Boolean {
        val settings = preferences.settings.value
        return !suspended &&
            settings.runsInBackground &&
            settings.armedVin != null &&
            _state.value.selected != null
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
            is ConnectionState.WaitingForBike,
            -> return
            else -> connectTo(target)
        }
    }

    private fun reconcile() {
        val settings = preferences.settings.value
        setPassiveWait(shouldWaitForBike())
        val connected = connection.state.value as? ConnectionState.Connected

        val phoneEndpoint = connected != null &&
            PhoneStatusProtocol.isSupportedBy(connected.services)
        val missedEndpoint = connected != null &&
            PhoneStatusProtocol.isMissedCallSupportedBy(connected.services)
        val canMirror = connected != null && settings.mirroring && phoneEndpoint
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
                callerMirroringAvailable = phoneEndpoint,
                missedCallMirroringAvailable = missedEndpoint,
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
        if (error != null || stoppedUnexpectedly) {
            linkLog.record(LinkLogKind.FAILURE, "Phone status mirroring stopped")
        }
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

    private fun setPassiveWait(enabled: Boolean) {
        connection.setWaitForBike(enabled)
        setPassiveWaitIntent(enabled)
    }

    /** Records a changed passive-wait intent without touching an in-flight connection run. */
    private fun setPassiveWaitIntent(enabled: Boolean) {
        if (enabled == passiveWaitArmed) return
        passiveWaitArmed = enabled
        passiveAttached = false
        linkLog.record(
            LinkLogKind.PASSIVE_WAIT,
            if (enabled) "Passive wait armed" else "Passive wait disarmed",
        )
        _state.update { it.copy(passiveWait = passiveWaitState()) }
    }

    private fun passiveWaitState(): PassiveWaitState = when {
        !passiveWaitArmed -> PassiveWaitState.OFF
        passiveAttached && connection.state.value is ConnectionState.Connected ->
            PassiveWaitState.ATTACHED
        connection.state.value is ConnectionState.WaitingForBike -> PassiveWaitState.WAITING
        else -> PassiveWaitState.ARMED
    }

    private fun logTransition(previous: ConnectionState?, current: ConnectionState) {
        if (passiveWaitArmed &&
            current is ConnectionState.Connected &&
            previous !is ConnectionState.Connected
        ) {
            passiveAttached = true
            linkLog.record(LinkLogKind.PASSIVE_WAIT, "Passive wait attached to the bike")
        }
        linkLog.record(LinkLogKind.TRANSITION, linkTransitionLogMessage(current))
        if (current is ConnectionState.Failed) {
            linkLog.record(LinkLogKind.FAILURE, linkFailureLogMessage(current.reason))
        }
    }

    /** Logs intent changes without ever writing the armed VIN or a Bluetooth address. */
    private fun logSettingChanges(previous: BikeLinkSettings, current: BikeLinkSettings) {
        fun record(message: String) = linkLog.record(LinkLogKind.SETTING, message)
        if (previous.mirroring != current.mirroring) {
            record(if (current.mirroring) "Mirroring armed" else "Mirroring paused")
        }
        if (previous.keepInBackground != current.keepInBackground) {
            record(
                if (current.keepInBackground) "Background mirroring on"
                else "Background mirroring off",
            )
        }
        if (previous.startOnBoot != current.startOnBoot) {
            record(if (current.startOnBoot) "Boot resume on" else "Boot resume off")
        }
        if (previous.mirrorCallerName != current.mirrorCallerName) {
            record(if (current.mirrorCallerName) "Caller-name mirroring on" else "Caller-name mirroring off")
        }
        if (previous.mirrorMissedCalls != current.mirrorMissedCalls) {
            record(if (current.mirrorMissedCalls) "Missed-call mirroring on" else "Missed-call mirroring off")
        }
        if (previous.armedVin != current.armedVin) {
            record(if (current.armedVin != null) "Bike armed for background link" else "Bike unarmed")
        }
    }

    companion object {
        const val MIRRORING_STOPPED_MESSAGE: String =
            "Phone status mirroring stopped. Reconnect and try again."
    }
}
