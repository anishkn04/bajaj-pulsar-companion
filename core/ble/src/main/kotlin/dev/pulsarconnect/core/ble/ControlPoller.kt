package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the cluster's read-only CONTROL channel while mirroring is on.
 *
 * This never acts on the returned counters: they are surfaced for the diagnostics screen until
 * a live cluster confirms what each change means. Polling stops on disconnect, an unsupported
 * peer, or a response the native parser would reject.
 */
class ControlPoller(
    private val connection: BikeConnection,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = ControlProtocol.POLL_INTERVAL_MILLIS,
) {
    init {
        require(intervalMillis > 0) { "Poll interval must be positive" }
    }

    private val _state = MutableStateFlow(ClusterControlState())
    val state: StateFlow<ClusterControlState> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0L

    val running: Boolean
        get() = job?.isActive == true

    /** Returns false when a poll loop is already running. */
    fun start(): Boolean {
        if (running) return false
        val run = ++generation
        _state.value = ClusterControlState(polling = true)
        job = scope.launch { pollLoop(run) }
        return true
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        _state.value = ClusterControlState()
    }

    private suspend fun pollLoop(run: Long) {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = try {
                    connection.readClusterControls()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                } ?: break
                _state.value = ClusterControlState(frame = frame, polling = true)
                delay(intervalMillis)
            }
        } finally {
            if (generation == run) _state.value = ClusterControlState()
        }
    }
}
