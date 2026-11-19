package dev.pulsarconnect.app.nav

import dev.pulsarconnect.core.ble.NavEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local hand-off from [dev.pulsarconnect.app.calls.NotificationCallListenerService] to the
 * bike link. Zero replay and bounded buffer so stale cues are dropped when the link is down.
 */
class NavNotificationBus {
    private val mutable = MutableSharedFlow<NavEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NavEvent> = mutable.asSharedFlow()

    fun publish(event: NavEvent) {
        mutable.tryEmit(event)
    }
}
