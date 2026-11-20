package dev.pulsarconnect.app.nav

import dev.pulsarconnect.core.ble.NavEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local hand-off from [dev.pulsarconnect.app.calls.NotificationCallListenerService] to the
 * bike link. The latest event is replayed so a route that started before the BLE writer subscribed
 * (for example, while the bike was still attaching) is not lost. The buffer stays bounded.
 */
class NavNotificationBus {
    private val mutable = MutableSharedFlow<NavEvent>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NavEvent> = mutable.asSharedFlow()

    fun publish(event: NavEvent) {
        mutable.tryEmit(event)
    }
}
