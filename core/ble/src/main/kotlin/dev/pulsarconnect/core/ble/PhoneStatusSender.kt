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
 * call triggers one fixed MISS_CALL frame ahead of the next status frame. There is a single
 * writer loop, so call and status frames can never interleave.
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
    }

    private suspend fun sendLoop() {
        var heartbeat = initialHeartbeat
        try {
            coroutineScope {
                val latest = MutableStateFlow<PhoneStatus?>(null)
                val call = MutableStateFlow(CallSnapshot())
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
                        val snapshot = call.value
                        val state = if (options.callerName) snapshot.state else CallState.NO_CALL
                        val name = if (options.callerName) snapshot.callerName else null
                        if (!connection.sendPhoneStatus(current, heartbeat, state, name)) break
                        heartbeat = nextHeartbeat(heartbeat)
                        markSent()
                        delay(intervalMillis)
                        status = latest.value ?: current
                    }
                } finally {
                    statusJob.cancel()
                    callJob.cancel()
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
