package dev.pulsarconnect.app.calls

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local hand-off from the system-bound notification listener to the bike link.
 *
 * No replay: an event that arrives while nothing is collecting (link down, mirroring off,
 * signed out) is dropped instead of being shown later. The buffer is small and bounded so a
 * stalled consumer cannot accumulate personal text.
 */
class CallNotificationBus {
    private val mutable = MutableSharedFlow<CallNotificationSignal>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val signals: SharedFlow<CallNotificationSignal> = mutable.asSharedFlow()

    fun publish(signal: CallNotificationSignal) {
        mutable.tryEmit(signal)
    }
}
