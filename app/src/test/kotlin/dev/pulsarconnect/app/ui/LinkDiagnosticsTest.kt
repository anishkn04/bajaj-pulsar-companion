package dev.pulsarconnect.app.ui

import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.LinkLogEntry
import dev.pulsarconnect.core.ble.LinkLogKind
import dev.pulsarconnect.core.ble.PassiveWaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDiagnosticsTest {

    private val vin = "MD2ABCDEFGH123456"
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val target = BikeTarget(id = mac, name = "Pulsar", vin = vin)

    private fun state(connection: ConnectionState = ConnectionState.Idle) = BleUiState(
        selected = target,
        connection = connection,
        adapterState = AdapterState.ON,
        settings = BikeLinkSettings(
            mirroring = true,
            keepInBackground = true,
            startOnBoot = true,
            armedVin = vin,
        ),
    )

    @Test
    fun `a connected mirroring link says so`() {
        val connected = ConnectionState.Connected(target, emptyList(), 0)
        val diagnostics = linkDiagnostics(
            state(connected).copy(phoneStatusSending = true),
        )

        assertEquals("Connected and mirroring phone status.", diagnostics.blocker)
    }

    @Test
    fun `permission and adapter blockers win over the connection state`() {
        val permission = linkDiagnostics(
            state().copy(missingPermissions = setOf(BlePermission.SCAN)),
        )
        assertTrue(permission.blocker.startsWith("Bluetooth permission is missing"))

        val bluetoothOff = linkDiagnostics(state().copy(adapterState = AdapterState.OFF))
        assertTrue(bluetoothOff.blocker.startsWith("Bluetooth is turned off"))
    }

    @Test
    fun `an unselected bike is the blocker`() {
        val diagnostics = linkDiagnostics(state().copy(selected = null))

        assertTrue(diagnostics.blocker.startsWith("No bike is selected"))
    }

    @Test
    fun `a paused mirroring intent explains why a connected bike is idle`() {
        val connected = ConnectionState.Connected(target, emptyList(), 0)
        val diagnostics = linkDiagnostics(
            state(connected).copy(
                settings = state().settings.copy(mirroring = false),
            ),
        )

        assertTrue(diagnostics.blocker.startsWith("Mirroring is paused"))
    }

    @Test
    fun `the last failure reason is shown with the passive wait state`() {
        val failed = ConnectionState.Failed(target, BleFailure.ConnectTimeout(10_000))
        val diagnostics = linkDiagnostics(
            state(failed).copy(passiveWait = PassiveWaitState.OFF),
        )

        assertEquals("Last attempt failed: Connection timed out.", diagnostics.blocker)
        assertEquals("Off", diagnostics.passiveWait)
    }

    @Test
    fun `a passive wait explains that the bike must turn on`() {
        val waiting = ConnectionState.WaitingForBike(target)
        val diagnostics = linkDiagnostics(
            state(waiting).copy(passiveWait = PassiveWaitState.WAITING),
        )

        assertTrue(diagnostics.blocker.contains("turn on"))
        assertEquals("Waiting for bike", diagnostics.passiveWait)
    }

    @Test
    fun `background and boot gating name the setting that turns them off`() {
        assertEquals("On", backgroundGating(state().settings))
        assertEquals(
            "Off — mirroring is paused",
            backgroundGating(state().settings.copy(mirroring = false)),
        )
        assertEquals(
            "Off — keep in background is disabled",
            backgroundGating(state().settings.copy(keepInBackground = false)),
        )

        assertEquals("On", bootGating(state().settings))
        assertEquals(
            "Off — resume after reboot is disabled",
            bootGating(state().settings.copy(startOnBoot = false)),
        )
        assertEquals(
            "Off — background mirroring is off",
            bootGating(state().settings.copy(keepInBackground = false)),
        )
        assertEquals(
            "Off — no bike is armed",
            bootGating(state().settings.copy(armedVin = null)),
        )
    }

    @Test
    fun `passive wait labels cover every state`() {
        assertEquals("Off", passiveWaitLabel(PassiveWaitState.OFF))
        assertEquals("Armed", passiveWaitLabel(PassiveWaitState.ARMED))
        assertEquals("Waiting for bike", passiveWaitLabel(PassiveWaitState.WAITING))
        assertEquals("Attached", passiveWaitLabel(PassiveWaitState.ATTACHED))
    }

    @Test
    fun `recent events are newest first and last-failure is the most recent one`() {
        val log = listOf(
            LinkLogEntry(1_000L, LinkLogKind.TRANSITION, "Connecting, attempt 1"),
            LinkLogEntry(2_000L, LinkLogKind.FAILURE, "Bike did not respond"),
            LinkLogEntry(3_000L, LinkLogKind.TRANSITION, "Waiting for the bike to turn on"),
            LinkLogEntry(4_000L, LinkLogKind.PASSIVE_WAIT, "Passive wait armed"),
        )

        val diagnostics = linkDiagnostics(state().copy(linkLog = log))

        assertEquals("Waiting for the bike to turn on", diagnostics.lastTransition?.message)
        assertEquals("Bike did not respond", diagnostics.lastFailure?.message)
        assertEquals(
            listOf(
                "Passive wait armed",
                "Waiting for the bike to turn on",
                "Bike did not respond",
                "Connecting, attempt 1",
            ),
            diagnostics.recent.map { it.message },
        )
    }

    @Test
    fun `recent events are capped for the card while the full log stays exportable`() {
        val log = (1..20).map { index ->
            LinkLogEntry(index.toLong(), LinkLogKind.TRANSITION, "event $index")
        }

        val diagnostics = linkDiagnostics(state().copy(linkLog = log))

        assertEquals(LINK_LOG_RECENT_LIMIT, diagnostics.recent.size)
        assertEquals("event 20", diagnostics.recent.first().message)
        assertEquals("event 13", diagnostics.recent.last().message)
    }
}
