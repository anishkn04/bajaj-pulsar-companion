package dev.pulsarconnect.core.ble

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object BluetoothUnavailable : ConnectionState
    data class MissingPermissions(val missing: Set<BlePermission>) : ConnectionState
    data class Connecting(val target: BikeTarget, val attempt: Int) : ConnectionState
    data class Discovering(val target: BikeTarget) : ConnectionState
    data class Connected(
        val target: BikeTarget,
        val services: List<BleService>,
        val sinceMillis: Long,
    ) : ConnectionState
    data class Reconnecting(
        val target: BikeTarget,
        val attempt: Int,
        val retryAtMillis: Long,
    ) : ConnectionState

    /**
     * Background mode is armed but the bike is not reachable yet. Android holds a passive GATT
     * connection and attaches by itself when the bike advertises; no scanning or polling runs.
     */
    data class WaitingForBike(val target: BikeTarget) : ConnectionState

    data class Failed(
        val target: BikeTarget?,
        val reason: BleFailure,
    ) : ConnectionState
}

/**
 * Connection/reconnect state machine with opt-in, capability-checked idle phone-status writes.
 *
 * [ConnectionState.Connected] is published only after a readiness probe
 * ([BleSession.requestMtu]) succeeds, so a cached GATT discovery alone never claims a live link.
 * Production passes [DEFAULT_HANDSHAKE_SETTLE_MILLIS] so a bonded cluster can finish LE
 * encryption before the first ATT request; tests leave the settle at zero for deterministic time.
 */
class BikeConnection(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
    private val handshakeSettleMillis: Long = 0,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    private val maxHandshakeAttempts: Int = DEFAULT_MAX_HANDSHAKE_ATTEMPTS,
    private val reconnectDelaysMillis: List<Long> = DEFAULT_RECONNECT_DELAYS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val stableConnectionMillis: Long = DEFAULT_STABLE_CONNECTION_MILLIS,
    private val passiveConnectTimeoutMillis: Long = DEFAULT_PASSIVE_CONNECT_TIMEOUT_MILLIS,
    private val waitingRetryDelaysMillis: List<Long> = DEFAULT_WAITING_RETRY_DELAYS,
    private val jitter: (Long) -> Long = { base ->
        (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
    },
) {
    init {
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
        require(discoveryTimeoutMillis > 0) { "Discovery timeout must be positive" }
        require(handshakeSettleMillis >= 0) { "Handshake settle delay must be nonnegative" }
        require(handshakeTimeoutMillis > 0) { "Handshake timeout must be positive" }
        require(maxHandshakeAttempts >= 1) { "At least one handshake attempt is required" }
        require(reconnectDelaysMillis.isNotEmpty()) { "At least one reconnect delay is required" }
        require(reconnectDelaysMillis.all { it >= 0 }) { "Reconnect delays must be nonnegative" }
        require(maxReconnectAttempts >= 0) { "Reconnect attempt limit must be nonnegative" }
        require(stableConnectionMillis >= 0) { "Stable connection duration must be nonnegative" }
        require(passiveConnectTimeoutMillis > 0) { "Passive connect timeout must be positive" }
        require(waitingRetryDelaysMillis.isNotEmpty()) { "At least one waiting retry delay is required" }
        require(waitingRetryDelaysMillis.all { it >= 0 }) { "Waiting retry delays must be nonnegative" }
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null
    private var session: BleSession? = null
    private var generation = 0L
    private var lastRun: Run? = null

    /**
     * Connects to [target]. An unexpected disconnect retries while [autoReconnect] is true.
     * Calling again replaces the previous target and closes its session.
     *
     * [waitForBike] keeps a background run alive across terminal failures: it asks Android for
     * passive GATT connections instead of immediate ones, so the phone attaches when the bike
     * powers on. Rider-facing foreground connects leave it off so failures surface quickly.
     */
    fun connect(
        target: BikeTarget,
        autoReconnect: Boolean = true,
        waitForBike: Boolean = false,
    ) {
        val run = Run(target, autoReconnect, waitForBike)
        lastRun = run
        val version = ++generation
        job?.cancel()
        job = null
        closeSession()
        _state.value = ConnectionState.Connecting(target, 0)
        job = scope.launch {
            try {
                runConnection(version, run)
            } finally {
                if (version == generation) {
                    closeSession()
                    if (!currentCoroutineContext().isActive) {
                        _state.value = ConnectionState.Idle
                    }
                }
            }
        }
    }

    /**
     * Toggles low-power waiting on the current run. Enabling revives a terminal failure;
     * disabling releases a passive wait. Does nothing without a run.
     */
    fun setWaitForBike(enabled: Boolean) {
        val run = lastRun ?: return
        if (run.waitForBike == enabled) return
        run.waitForBike = enabled
        val current = _state.value
        if (enabled) {
            if (current is ConnectionState.Failed) {
                connect(run.target, run.autoReconnect, waitForBike = true)
            }
        } else if (current is ConnectionState.WaitingForBike) {
            cancelRun()
        }
    }

    /**
     * Updates the current run and manual retry policy without replacing a healthy session.
     * Disabling cancels pending automatic waits; enabling never starts a connection by itself.
     */
    fun setAutoReconnect(enabled: Boolean) {
        val run = lastRun ?: return
        run.autoReconnect = enabled
        val current = _state.value
        val waitingToReconnect = current is ConnectionState.Reconnecting ||
            current is ConnectionState.WaitingForBike ||
            (current is ConnectionState.BluetoothUnavailable && job?.isActive == true)
        if (!enabled && waitingToReconnect) cancelRun()
    }

    /** Retries the last target after a failure or a disabled adapter. */
    fun retry() {
        lastRun?.let { connect(it.target, it.autoReconnect, it.waitForBike) }
    }

    fun stop() {
        lastRun = null
        cancelRun()
    }

    /**
     * Sends the GENERAL phone-status frame to the recovered writable endpoint of the current
     * connection. [callState] and [callerName] are only meaningful while an incoming call is
     * ringing or active; [CallerName] guarantees a phone number is never written.
     * Unsupported/disconnected sessions return false; write failures and cancellation propagate.
     */
    suspend fun sendPhoneStatus(
        status: PhoneStatus,
        heartbeat: Int,
        callState: CallState = CallState.NO_CALL,
        callerName: String? = null,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val version = generation
        val connected = _state.value as? ConnectionState.Connected ?: return false
        val activeSession = session ?: return false
        fun isCurrent(): Boolean = version == generation && session === activeSession &&
            _state.value === connected
        if (!PhoneStatusProtocol.isSupportedBy(connected.services)) return false
        if (!isCurrent()) return false
        // Negotiate before every payload that exceeds the default ATT size. The transport
        // caches a successful negotiation, so this is cheap after the first write.
        val mtu = activeSession.requestMtu(PhoneStatusProtocol.PREFERRED_MTU)
        if (mtu < PhoneStatusProtocol.REQUIRED_MTU) {
            throw BleException(BleFailure.MtuTooSmall(PhoneStatusProtocol.REQUIRED_MTU, mtu))
        }
        val bytes = PhoneStatusProtocol.encode(status, heartbeat, callState, callerName)
        if (!isCurrent()) return false
        // Never re-read session for the write: a replacement must not receive this payload.
        activeSession.write(
            PhoneStatusProtocol.SERVICE_UUID,
            PhoneStatusProtocol.GENERAL_UUID,
            bytes,
            withResponse = true,
        )
        currentCoroutineContext().ensureActive()
        // An automatic reconnect can replace the session without changing the run generation.
        return isCurrent()
    }

    /**
     * Sends the fixed missed-call icon to the writable MISS_CALL endpoint.
     *
     * Returns false when the peer does not expose the endpoint or the negotiated MTU cannot hold
     * the 74-byte frame; neither case is a link failure. Write failures and cancellation propagate.
     */
    suspend fun sendMissedCall(): Boolean {
        currentCoroutineContext().ensureActive()
        val version = generation
        val connected = _state.value as? ConnectionState.Connected ?: return false
        val activeSession = session ?: return false
        fun isCurrent(): Boolean = version == generation && session === activeSession &&
            _state.value === connected
        if (!PhoneStatusProtocol.isMissedCallSupportedBy(connected.services)) return false
        if (!isCurrent()) return false
        val mtu = activeSession.requestMtu(PhoneStatusProtocol.PREFERRED_MTU)
        if (mtu < PhoneStatusProtocol.MISSED_CALL_REQUIRED_MTU) return false
        val bytes = PhoneStatusProtocol.encodeMissedCall()
        if (!isCurrent()) return false
        activeSession.write(
            PhoneStatusProtocol.SERVICE_UUID,
            PhoneStatusProtocol.MISSED_CALL_UUID,
            bytes,
            withResponse = true,
        )
        currentCoroutineContext().ensureActive()
        return isCurrent()
    }

    /**
     * Reads the cluster's CONTROL characteristic without acting on it. Returns null when the
     * link is not connected, the peer does not expose CONTROL, or the response is too short.
     */
    suspend fun readClusterControls(): ClusterControlFrame? {
        currentCoroutineContext().ensureActive()
        val version = generation
        val connected = _state.value as? ConnectionState.Connected ?: return null
        val activeSession = session ?: return null
        fun isCurrent(): Boolean = version == generation && session === activeSession &&
            _state.value === connected
        if (!ControlProtocol.isSupportedBy(connected.services)) return null
        if (!isCurrent()) return null
        val bytes = activeSession.read(ControlProtocol.SERVICE_UUID, ControlProtocol.CONTROL_UUID)
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        return ControlProtocol.parse(bytes)
    }

    private fun cancelRun() {
        generation++
        job?.cancel()
        job = null
        closeSession()
        _state.value = ConnectionState.Idle
    }

    private suspend fun runConnection(
        version: Long,
        run: Run,
    ) {
        val target = run.target
        var attempt = 0
        var handshakeFailures = 0
        while (version == generation) {
            currentCoroutineContext().ensureActive()

            if (attempt > 0 && run.waitForBike) {
                // Passive waits are event-driven; this delay only spaces out repeated failures
                // (for example a stack that rejects background connections immediately).
                publish(version, ConnectionState.WaitingForBike(target))
                delay(jitter(waitingDelayFor(attempt)).coerceAtLeast(0))
                if (version != generation) return
            } else if (attempt > 0) {
                val wait = jitter(delayFor(attempt)).coerceAtLeast(0)
                publish(version, ConnectionState.Reconnecting(target, attempt, clock() + wait))
                delay(wait)
                if (version != generation) return
            } else if (run.waitForBike) {
                publish(version, ConnectionState.WaitingForBike(target))
            }

            if (transport.adapterState.value == AdapterState.OFF) {
                publish(version, ConnectionState.BluetoothUnavailable)
                if (!run.waitForBike || !run.autoReconnect) return
                transport.adapterState.first { it == AdapterState.ON }
                if (version != generation) return
                attempt = 0
                continue
            }
            val missing = transport.missingPermissions()
            if (missing.isNotEmpty()) {
                publish(version, ConnectionState.MissingPermissions(missing))
                return
            }

            if (!run.waitForBike) {
                publish(version, ConnectionState.Connecting(target, attempt))
            }
            val newSession = try {
                if (run.waitForBike) {
                    withTimeout(passiveConnectTimeoutMillis) {
                        transport.connect(target, passive = true)
                    }
                } else {
                    withTimeout(connectTimeoutMillis) { transport.connect(target) }
                }
            } catch (timeout: TimeoutCancellationException) {
                if (run.waitForBike) {
                    // The bike stayed off for the whole passive window; re-arm immediately.
                    attempt++
                    continue
                }
                if (attempt == 0) {
                    publish(version, ConnectionState.Failed(target, BleFailure.ConnectTimeout(connectTimeoutMillis)))
                    return
                }
                attempt = nextAttempt(version, target, attempt) ?: return
                continue
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: BleException) {
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                if (attempt == 0) {
                    publish(version, ConnectionState.Failed(target, error.failure))
                    return
                }
                attempt = nextAttempt(version, target, attempt) ?: return
                continue
            } catch (error: Exception) {
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                if (attempt == 0) {
                    publish(version, ConnectionState.Failed(target, unexpected(error)))
                    return
                }
                attempt = nextAttempt(version, target, attempt) ?: return
                continue
            }
            if (version != generation) {
                newSession.close()
                return
            }
            session = newSession

            publish(version, ConnectionState.Discovering(target))
            val services = try {
                withTimeout(discoveryTimeoutMillis) { newSession.services() }
            } catch (timeout: TimeoutCancellationException) {
                closeSession(newSession)
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                if (attempt == 0) {
                    publish(version, ConnectionState.Failed(target, BleFailure.DiscoveryTimeout(discoveryTimeoutMillis)))
                    return
                }
                attempt = nextAttempt(version, target, attempt) ?: return
                continue
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: BleException) {
                closeSession(newSession)
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                if (attempt == 0) {
                    publish(version, ConnectionState.Failed(target, error.failure))
                    return
                }
                attempt = nextAttempt(version, target, attempt) ?: return
                continue
            } catch (error: Exception) {
                closeSession(newSession)
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                if (attempt == 0) {
                    publish(
                        version,
                        ConnectionState.Failed(
                            target,
                            BleFailure.DiscoveryFailed(error.message ?: "Service discovery failed"),
                        ),
                    )
                    return
                }
                attempt = nextAttempt(version, target, attempt) ?: return
                continue
            }
            if (version != generation) {
                closeSession(newSession)
                return
            }

            // Discovery can be served from Android's cache in milliseconds, while LE encryption
            // is still completing. A request sent during the SMP handshake can be dropped by
            // the cluster, so settle briefly and then prove the ATT layer is live before
            // claiming a link. No payload is written here.
            if (handshakeSettleMillis > 0) delay(handshakeSettleMillis)
            if (version != generation) {
                closeSession(newSession)
                return
            }
            val probeMtu = try {
                withTimeout(handshakeTimeoutMillis) {
                    newSession.requestMtu(PhoneStatusProtocol.PREFERRED_MTU)
                }
            } catch (timeout: TimeoutCancellationException) {
                closeSession(newSession)
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                handshakeFailures++
                if (handshakeFailures >= maxHandshakeAttempts) {
                    publish(version, ConnectionState.Failed(target, BleFailure.NotResponding))
                    return
                }
                attempt++
                continue
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                closeSession(newSession)
                val disconnected = (error as? BleException)?.failure as? BleFailure.Disconnected
                if (disconnected != null) {
                    // The link ended while it was being verified. Follow the same policy as a
                    // post-connection drop instead of treating it as a silent probe failure.
                    if (!run.autoReconnect) {
                        publish(version, ConnectionState.Failed(target, disconnected))
                        return
                    }
                    if (run.waitForBike) {
                        attempt++
                        continue
                    }
                    attempt++
                    if (attempt > maxReconnectAttempts) {
                        publish(
                            version,
                            ConnectionState.Failed(
                                target,
                                BleFailure.TooManyAttempts(maxReconnectAttempts),
                            ),
                        )
                        return
                    }
                    continue
                }
                if (run.waitForBike) {
                    attempt++
                    continue
                }
                handshakeFailures++
                if (handshakeFailures >= maxHandshakeAttempts) {
                    publish(version, ConnectionState.Failed(target, BleFailure.NotResponding))
                    return
                }
                attempt++
                continue
            }
            if (probeMtu < PhoneStatusProtocol.REQUIRED_MTU) {
                closeSession(newSession)
                publish(
                    version,
                    ConnectionState.Failed(
                        target,
                        BleFailure.MtuTooSmall(PhoneStatusProtocol.REQUIRED_MTU, probeMtu),
                    ),
                )
                return
            }
            handshakeFailures = 0

            publish(version, ConnectionState.Connected(target, services, clock()))
            if (run.waitForBike) attempt = 0
            val connectedAt = clock()
            val outcome = try {
                awaitDisconnect(newSession)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                DisconnectOutcome.Remote(status = -1)
            }
            if (version != generation) return
            closeSession(newSession)

            when (outcome) {
                DisconnectOutcome.AdapterOff -> {
                    publish(version, ConnectionState.BluetoothUnavailable)
                    if (!run.autoReconnect) return
                    transport.adapterState.first { it == AdapterState.ON }
                    if (version != generation) return
                    attempt = 0
                }
                is DisconnectOutcome.Remote -> {
                    if (!run.autoReconnect) {
                        publish(
                            version,
                            ConnectionState.Failed(target, BleFailure.Disconnected(outcome.status)),
                        )
                        return
                    }
                    if (run.waitForBike) {
                        // Keep the background link armed; a passive connection reattaches when
                        // the bike powers back on, without polling or an attempt limit.
                        attempt = 1
                        continue
                    }
                    attempt = if (clock() - connectedAt >= stableConnectionMillis) {
                        1
                    } else {
                        attempt + 1
                    }
                    if (attempt > maxReconnectAttempts) {
                        publish(
                            version,
                            ConnectionState.Failed(
                                target,
                                BleFailure.TooManyAttempts(maxReconnectAttempts),
                            ),
                        )
                        return
                    }
                }
            }
        }
    }

    private fun nextAttempt(version: Long, target: BikeTarget, attempt: Int): Int? {
        val next = attempt + 1
        if (next > maxReconnectAttempts) {
            publish(
                version,
                ConnectionState.Failed(target, BleFailure.TooManyAttempts(maxReconnectAttempts)),
            )
            return null
        }
        return next
    }

    private suspend fun awaitDisconnect(
        session: BleSession,
    ): DisconnectOutcome = kotlinx.coroutines.coroutineScope {
        val outcome = CompletableDeferred<DisconnectOutcome>()
        val eventsJob = launch {
            session.events.collect { event ->
                if (event is BleSessionEvent.Disconnected) {
                    outcome.complete(DisconnectOutcome.Remote(event.status))
                }
            }
        }
        val adapterJob = launch {
            transport.adapterState.collect { state ->
                if (state == AdapterState.OFF) outcome.complete(DisconnectOutcome.AdapterOff)
            }
        }
        try {
            outcome.await()
        } finally {
            eventsJob.cancel()
            adapterJob.cancel()
        }
    }

    private fun publish(version: Long, state: ConnectionState) {
        if (version == generation) _state.value = state
    }

    private fun closeSession(expected: BleSession? = session) {
        val current = session ?: return
        // A cancelled discovery may finish after connect() has installed a new session.
        if (current !== expected) return
        session = null
        try {
            current.close()
        } catch (_: Exception) {
            // Closing is best effort; a stale GATT client must not break the state machine.
        }
    }

    private fun delayFor(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, reconnectDelaysMillis.lastIndex)
        return reconnectDelaysMillis[index]
    }

    private fun waitingDelayFor(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, waitingRetryDelaysMillis.lastIndex)
        return waitingRetryDelaysMillis[index]
    }

    private fun unexpected(error: Exception): BleFailure =
        BleFailure.Unexpected(error.message ?: error::class.simpleName ?: "Unknown Bluetooth error")

    private data class Run(
        val target: BikeTarget,
        var autoReconnect: Boolean,
        var waitForBike: Boolean,
    )

    private sealed interface DisconnectOutcome {
        data object AdapterOff : DisconnectOutcome
        data class Remote(val status: Int) : DisconnectOutcome
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 15_000
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS: Long = 15_000
        const val DEFAULT_HANDSHAKE_SETTLE_MILLIS: Long = 1_500
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS: Long = 6_000
        const val DEFAULT_MAX_HANDSHAKE_ATTEMPTS: Int = 3
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS: Int = 10
        const val DEFAULT_STABLE_CONNECTION_MILLIS: Long = 60_000
        val DEFAULT_RECONNECT_DELAYS: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

        /**
         * How long a single passive connection may wait for the bike to advertise. This is a
         * watchdog, not a poll interval: the platform attaches as soon as the bike appears.
         */
        const val DEFAULT_PASSIVE_CONNECT_TIMEOUT_MILLIS: Long = 5 * 60_000

        /** Spacing between passive re-arms when the stack reports an immediate failure. */
        val DEFAULT_WAITING_RETRY_DELAYS: List<Long> =
            listOf(10_000, 30_000, 60_000, 2 * 60_000)
    }
}
