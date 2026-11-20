package dev.pulsarconnect.core.ble

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Sends the latest phone status once per second while the bike link is connected.
 *
 * Battery/signal always flow at the native cadence. Call events are opt-in through
 * [callOptions]: caller text is added to the GENERAL frame while ringing/active, and a missed
 * call triggers one fixed MISS_CALL frame ahead of the next status frame. Experimental turn-by-turn
 * navigation cues are opt-in through [navOptions] and ride in the same 30-byte text slot only
 * when no real phone call is ringing or active. There is a single writer loop, so call, nav, and
 * status frames can never interleave.
 *
 * The caller owns start/stop. [onStopped] runs once when the loop exits on its own: with a
 * failure when sending failed, or with `null` after a clean stop such as a disconnect or an
 * unsupported peer. Cancelling the job does not report a failure.
 */
class PhoneStatusSender(
    private val connection: BikeConnection,
    statuses: Flow<PhoneStatus>,
    private val scope: CoroutineScope,
    private val callEvents: Flow<CallEvent> = emptyFlow(),
    private val callOptions: () -> CallMirrorOptions = { CallMirrorOptions() },
    private val navEvents: Flow<NavEvent> = emptyFlow(),
    private val navOptions: () -> NavMirrorOptions = { NavMirrorOptions() },
    private val onNavPreview: (String?) -> Unit = {},
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val initialHeartbeat: Int = 1,
    private val onStopped: (Throwable?) -> Unit = {},
    private val onSent: () -> Unit = {},
) {
    init {
        require(intervalMillis > 0) { "Send interval must be positive" }
        require(initialHeartbeat in 0..255) { "Initial heartbeat must be a wire byte" }
    }

    private val statuses: Flow<PhoneStatus> = statuses
    private var job: Job? = null

    @Volatile
    private var sentThisRun: Boolean = false

    val running: Boolean
        get() = job?.isActive == true

    /**
     * True only while this run is alive and has completed at least one write. Callers must gate
     * "mirroring active" on this rather than on [running], which becomes true before the first
     * GATT write has been acknowledged.
     */
    val hasSent: Boolean
        get() = sentThisRun && running

    /** Returns false when a sender loop is already running. */
    fun start(): Boolean {
        if (running) return false
        sentThisRun = false
        job = scope.launch { sendLoop() }
        return true
    }

    fun stop() {
        sentThisRun = false
        job?.cancel()
        job = null
        onNavPreview(null)
    }

    private suspend fun sendLoop() {
        var heartbeat = initialHeartbeat
        try {
            coroutineScope {
                val latest = MutableStateFlow<PhoneStatus?>(null)
                val call = MutableStateFlow(CallSnapshot())
                val nav = MutableStateFlow<String?>(null)
                val missedCallPending = AtomicBoolean(false)

                val statusJob = launch { statuses.collect { latest.value = it } }
                val callJob = launch {
                    callEvents.collect { event ->
                        when (event) {
                            is CallEvent.Incoming -> if (callOptions().callerName) {
                                call.value = CallSnapshot(CallState.INCOMING_CALL, event.callerName)
                            }
                            is CallEvent.Active -> if (callOptions().callerName) {
                                call.value = CallSnapshot(CallState.ACTIVE_CALL, event.callerName)
                            }
                            CallEvent.Ended -> call.value = CallSnapshot()
                            CallEvent.Missed -> if (callOptions().missedCalls) {
                                missedCallPending.set(true)
                            }
                        }
                    }
                }
                val navJob = launch {
                    navEvents.collect { event ->
                        when (event) {
                            is NavEvent.Update -> if (navOptions().enabled) {
                                val wire = NavText.wireText(event.text).ifEmpty { null }
                                nav.value = wire
                                onNavPreview(wire)
                            }
                            NavEvent.Ended -> {
                                nav.value = null
                                onNavPreview(null)
                            }
                        }
                    }
                }
                try {
                    // Let the first reading arrive before deciding whether this peer supports writes.
                    yield()
                    var status = latest.value
                    while (isActive) {
                        if (connection.state.value !is ConnectionState.Connected) break
                        val current = latest.value ?: status
                        if (current == null) {
                            delay(intervalMillis)
                            status = latest.value
                            continue
                        }
                        // The missed-call icon has priority over the periodic status frame. Re-check
                        // the switch at send time so disabling it cannot leak one last frame.
                        if (missedCallPending.getAndSet(false) && callOptions().missedCalls) {
                            if (connection.sendMissedCall()) markSent()
                        }
                        val options = callOptions()
                        val navOpts = navOptions()
                        val snapshot = call.value
                        val realCallActive = options.callerName &&
                            (snapshot.state == CallState.INCOMING_CALL || snapshot.state == CallState.ACTIVE_CALL)
                        val activeNav = if (navOpts.enabled) nav.value else null
                        if (!navOpts.enabled && nav.value != null) {
                            nav.value = null
                            onNavPreview(null)
                        }

                        val sent = when {
                            realCallActive -> connection.sendPhoneStatus(
                                status = current,
                                heartbeat = heartbeat,
                                callState = snapshot.state,
                                callerName = snapshot.callerName,
                            )
                            activeNav != null -> connection.sendPhoneStatus(
                                status = current,
                                heartbeat = heartbeat,
                                callState = CallState.INCOMING_CALL,
                                navText = activeNav,
                            )
                            else -> connection.sendPhoneStatus(
                                status = current,
                                heartbeat = heartbeat,
                                callState = CallState.NO_CALL,
                                callerName = null,
                            )
                        }
                        if (!sent) break
                        heartbeat = nextHeartbeat(heartbeat)
                        markSent()
                        delay(intervalMillis)
                        status = latest.value ?: current
                    }
                } finally {
                    statusJob.cancel()
                    callJob.cancel()
                    navJob.cancel()
                    onNavPreview(null)
                }
            }
            onStopped(null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            onStopped(error)
        }
    }

    private fun markSent() {
        if (!sentThisRun) {
            sentThisRun = true
            onSent()
        }
    }

    private fun nextHeartbeat(current: Int): Int = if (current == 255) 0 else current + 1

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000
    }
}
