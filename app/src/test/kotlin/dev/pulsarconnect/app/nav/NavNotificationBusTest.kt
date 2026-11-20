package dev.pulsarconnect.app.nav

import dev.pulsarconnect.core.ble.NavEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class NavNotificationBusTest {

    @Test
    fun `replays latest cue to a subscriber that arrives after the bike connects`() = runTest {
        val bus = NavNotificationBus()

        bus.publish(NavEvent.Update("> 250m MG Rd"))

        val received = withTimeout(1_000) { bus.events.first() }
        assertEquals(NavEvent.Update("> 250m MG Rd"), received)
    }

    @Test
    fun `replays route ended so a late subscriber clears stale navigation`() = runTest {
        val bus = NavNotificationBus()
        bus.publish(NavEvent.Update("> 250m MG Rd"))
        bus.publish(NavEvent.Ended)

        val received = withTimeout(1_000) { bus.events.first() }
        assertEquals(NavEvent.Ended, received)
    }
}
