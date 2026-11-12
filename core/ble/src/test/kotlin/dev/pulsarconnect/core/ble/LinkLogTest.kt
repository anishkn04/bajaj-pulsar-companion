package dev.pulsarconnect.core.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkLogTest {

    private val vin = "MD2ABCDEFGH123456"
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val target = testTarget(vin = vin)

    @Test
    fun `the ring buffer keeps the newest entries in order`() {
        var now = 1_700_000_000_000L
        val log = LinkLog(capacity = 3) { now++ }

        repeat(5) { index -> log.record(LinkLogKind.TRANSITION, "event $index") }

        assertEquals(listOf("event 2", "event 3", "event 4"), log.snapshot().map { it.message })
        assertEquals(listOf("event 2", "event 3", "event 4"), log.entries.value.map { it.message })
        assertEquals(listOf(1_700_000_000_002L, 1_700_000_000_003L, 1_700_000_000_004L),
            log.snapshot().map { it.timestampMillis })
    }

    @Test
    fun `clear empties the buffer and its published flow`() {
        val log = LinkLog()
        log.record(LinkLogKind.SETTING, "Mirroring armed")

        log.clear()

        assertTrue(log.snapshot().isEmpty())
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `transition messages describe the state without identifiers`() {
        val connected = linkTransitionLogMessage(
            ConnectionState.Connected(target, listOf(service()), 0),
        )
        assertEquals("Connected, 1 service", connected)

        val connecting = linkTransitionLogMessage(ConnectionState.Connecting(target, 2))
        assertEquals("Connecting, attempt 3", connecting)

        val waiting = linkTransitionLogMessage(ConnectionState.WaitingForBike(target))
        assertEquals("Waiting for the bike to turn on", waiting)

        val missing = linkTransitionLogMessage(
            ConnectionState.MissingPermissions(setOf(BlePermission.SCAN, BlePermission.CONNECT)),
        )
        assertEquals("Missing Bluetooth permission: scan, connect", missing)

        for (message in listOf(connected, connecting, waiting, missing)) {
            assertFalse(message.contains(vin))
            assertFalse(message.contains(mac))
        }
    }

    @Test
    fun `failed transitions include a readable failure reason`() {
        val message = linkTransitionLogMessage(
            ConnectionState.Failed(target, BleFailure.ConnectTimeout(10_000)),
        )
        assertEquals("Link failed: Connection timed out", message)

        assertEquals("Bluetooth is off", linkFailureLogMessage(BleFailure.AdapterOff))
        assertEquals(
            "Too many reconnect attempts (4)",
            linkFailureLogMessage(BleFailure.TooManyAttempts(4)),
        )
    }

    @Test
    fun `export redacts MAC and VIN-shaped text`() {
        val entries = listOf(
            LinkLogEntry(1_700_000_000_000L, LinkLogKind.TRANSITION, "Connected to $mac"),
            LinkLogEntry(1_700_000_001_000L, LinkLogKind.FAILURE, "Bike $vin did not respond"),
            LinkLogEntry(1_700_000_002_000L, LinkLogKind.PASSIVE_WAIT, "Passive wait armed"),
        )

        val exported = exportLinkLog(entries)

        assertTrue(exported.startsWith("Pulsar Connect link log"))
        assertTrue(exported.contains("LINK: Connected to [redacted-mac]"))
        assertTrue(exported.contains("FAILURE: Bike [redacted-vin] did not respond"))
        assertTrue(exported.contains("PASSIVE: Passive wait armed"))
        assertFalse(exported.contains(vin))
        assertFalse(exported.contains(mac))
    }

    @Test
    fun `redaction leaves ordinary words and numbers untouched`() {
        val text = "Reconnecting, attempt 2 after GATT error 133"

        assertEquals(text, redactLinkLogText(text))
    }

    private fun service() = BleService(
        PhoneStatusProtocol.SERVICE_UUID,
        emptyList(),
    )
}
