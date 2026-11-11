package dev.pulsarconnect.app.ui

import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.ConnectionState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkStatusTextTest {

    private val target = BikeTarget(id = "AA:BB:CC:DD:EE:FF", name = "Pulsar", vin = "MD2ABCDEFGH123456")

    @Test
    fun `not responding is reported truthfully instead of connected`() {
        val connection = ConnectionState.Failed(target, BleFailure.NotResponding)

        assertEquals("Bike not responding", bleStatusValue(connection))
        assertTrue(bleStatusDetail(BleUiState(connection = connection)).contains("did not answer"))
    }

    @Test
    fun `attempt exhaustion is reported as not responding`() {
        val connection = ConnectionState.Failed(target, BleFailure.TooManyAttempts(10))

        assertEquals("Bike not responding", bleStatusValue(connection))
        assertTrue(bleStatusDetail(BleUiState(connection = connection)).contains("did not answer"))
    }

    @Test
    fun `other failures keep a connection failed headline with readable detail`() {
        val connection = ConnectionState.Failed(target, BleFailure.GattError("writeCharacteristic", 133))

        assertEquals("Connection failed", bleStatusValue(connection))
        val detail = bleStatusDetail(BleUiState(connection = connection))
        assertTrue(detail.contains("Bluetooth reported an error"))
        assertFalse(detail.contains("GattError"))
    }

    @Test
    fun `a passive background wait is shown truthfully`() {
        val connection = ConnectionState.WaitingForBike(target)

        assertEquals("Waiting for bike…", bleStatusValue(connection))
        assertTrue(bleStatusDetail(BleUiState(connection = connection)).contains("attach by itself"))
    }

    @Test
    fun `enabled but not sending never claims mirroring is active`() {
        val state = BleUiState(
            connection = connected(),
            phoneStatusEnabled = true,
            phoneStatusSending = false,
        )

        val detail = bleStatusDetail(state)
        assertTrue(detail.contains("enabled but not sending"))
        assertFalse(detail.contains("mirroring is active"))
    }

    @Test
    fun `only a sending state claims mirroring is active`() {
        val state = BleUiState(
            connection = connected(),
            phoneStatusEnabled = true,
            phoneStatusSending = true,
        )

        assertTrue(bleStatusDetail(state).contains("mirroring is active"))
    }

    @Test
    fun `mirroring stopped wins over the enabled state`() {
        val state = BleUiState(
            connection = connected(),
            phoneStatusEnabled = true,
            phoneStatusSending = false,
            phoneStatusError = "stopped",
        )

        assertTrue(bleStatusDetail(state).contains("mirroring is stopped"))
    }

    private fun connected(): ConnectionState.Connected = ConnectionState.Connected(
        target = target,
        services = listOf(BleService(UUID.randomUUID(), emptyList())),
        sinceMillis = 0,
    )
}
