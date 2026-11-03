package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Sends the latest idle battery/signal reading once per second while the bike link is connected.
 *
 * The caller owns start/stop. [onStopped] runs once when the loop exits on its own: with a
 * failure when sending failed, or with `null` after a clean stop such as a disconnect or an
 * unsupported peer. Cancelling the job does not report a failure.
 */
class PhoneStatusSender(
    private val connection: BikeConnection,
    statuses: Flow<PhoneStatus>,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val initialHeartbeat: Int = 1,
    private val onStopped: (Throwable?) -> Unit = {},
) {
    init {
        require(intervalMillis > 0) { "Send interval must be positive" }
        require(initialHeartbeat in 0..255) { "Initial heartbeat must be a wire byte" }
    }

    private val statuses: Flow<PhoneStatus> = statuses
    private var job: Job? = null

    val running: Boolean
        get() = job?.isActive == true

    /** Returns false when a sender loop is already running. */
    fun start(): Boolean {
        if (running) return false
        job = scope.launch { sendLoop() }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sendLoop() {
        var heartbeat = initialHeartbeat
        try {
            coroutineScope {
                val latest = MutableStateFlow<PhoneStatus?>(null)
                val statusJob = launch { statuses.collect { latest.value = it } }
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
                        if (!connection.sendPhoneStatus(current, heartbeat)) break
                        heartbeat = nextHeartbeat(heartbeat)
                        delay(intervalMillis)
                        status = latest.value ?: current
                    }
                } finally {
                    statusJob.cancel()
                }
            }
            onStopped(null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            onStopped(error)
        }
    }

    private fun nextHeartbeat(current: Int): Int = if (current == 255) 0 else current + 1

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000
    }
}
