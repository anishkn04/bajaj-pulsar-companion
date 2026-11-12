package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeLinkState
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkNotificationTest {

    private val vin = "MD2ABCDEFGH123456"
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val target = BikeTarget(id = mac, name = "Pulsar N160", vin = vin)

    private fun state(connection: ConnectionState, mirroringActive: Boolean = true) = BikeLinkState(
        vin = vin,
        selected = target,
        connection = connection,
        settings = BikeLinkSettings(mirroring = true, armedVin = vin),
        mirroringActive = mirroringActive,
    )

    @Test
    fun `the advertised bike name is shown when it is not an identifier`() {
        assertEquals("Pulsar N160", bikeLinkDisplayName("Pulsar N160"))
        assertEquals("Pulsar N160", bikeLinkDisplayName("  Pulsar N160  "))
    }

    @Test
    fun `a missing, VIN-shaped, or MAC-shaped name falls back to a neutral label`() {
        assertEquals(DEFAULT_BIKE_LINK_NAME, bikeLinkDisplayName(null))
        assertEquals(DEFAULT_BIKE_LINK_NAME, bikeLinkDisplayName("  "))
        assertEquals(DEFAULT_BIKE_LINK_NAME, bikeLinkDisplayName(vin))
        assertEquals(DEFAULT_BIKE_LINK_NAME, bikeLinkDisplayName(mac))
        assertEquals(DEFAULT_BIKE_LINK_NAME, bikeLinkDisplayName("Bike $mac"))
    }

    @Test
    fun `link state is readable and never repeats a VIN or MAC`() {
        val connected = bikeLinkNotificationState(
            state(ConnectionState.Connected(target, emptyList(), 0)),
        )
        assertTrue(connected.contains("mirroring phone status"))

        val waiting = bikeLinkNotificationState(state(ConnectionState.WaitingForBike(target)))
        assertTrue(waiting.contains("turn on"))

        val failed = bikeLinkNotificationState(
            state(ConnectionState.Failed(target, BleFailure.NotResponding)),
        )
        assertTrue(failed.contains("open the app"))

        for (text in listOf(connected, waiting, failed)) {
            assertFalse(text.contains(vin))
            assertFalse(text.contains(mac))
        }
    }

    @Test
    fun `paused and stopped mirroring are described truthfully`() {
        val paused = bikeLinkNotificationState(
            state(ConnectionState.Connected(target, emptyList(), 0)).copy(
                settings = BikeLinkSettings(mirroring = false, armedVin = vin),
            ),
        )
        assertEquals("Connected · mirroring paused", paused)

        val stopped = bikeLinkNotificationState(
            state(ConnectionState.Connected(target, emptyList(), 0), mirroringActive = false),
        )
        assertEquals("Connected · mirroring is off", stopped)
    }
}
