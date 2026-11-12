package dev.pulsarconnect.app.ui

import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BlePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupHealthTest {

    private val bike = BikeTarget(id = "AA:BB:CC:DD:EE:FF", name = "Pulsar", vin = "MD2ABCDEFGH123456")

    private fun readyState() = BleUiState(
        selected = bike,
        notificationAccessGranted = false,
        settings = BikeLinkSettings(
            mirroring = true,
            keepInBackground = true,
            startOnBoot = true,
            armedVin = "MD2ABCDEFGH123456",
        ),
    )

    private fun evaluate(
        state: BleUiState = readyState(),
        notificationsGranted: Boolean = true,
        batteryIgnored: Boolean = true,
    ) = setupHealth(state, notificationsGranted, batteryIgnored)

    private fun SetupHealth.item(action: SetupHealthAction) = items.single { it.action == action }

    @Test
    fun `a fully configured link is ready to mirror`() {
        val health = evaluate()

        assertTrue(health.ready)
        assertEquals(0, health.attentionCount)
        assertEquals("Ready to mirror", health.summary)
        assertTrue(health.items.none { it.needsAttention })
        assertEquals(7, health.items.size)
    }

    @Test
    fun `missing Bluetooth permissions are reported and point at the bike screen`() {
        val health = evaluate(
            state = readyState().copy(missingPermissions = setOf(BlePermission.SCAN, BlePermission.CONNECT)),
        )

        val item = health.item(SetupHealthAction.BIKE_PERMISSIONS)
        assertTrue(item.needsAttention)
        assertEquals("2 missing", item.status)
        assertEquals(1, health.attentionCount)
        assertEquals("1 item needs attention", health.summary)
    }

    @Test
    fun `notification access only needs attention when a call feature is on`() {
        val unused = evaluate()
        val unusedItem = unused.item(SetupHealthAction.NOTIFICATION_ACCESS)
        assertEquals("Not needed", unusedItem.status)
        assertFalse(unusedItem.needsAttention)

        val needed = evaluate(
            state = readyState().copy(
                settings = readyState().settings.copy(mirrorCallerName = true),
            ),
        )
        val neededItem = needed.item(SetupHealthAction.NOTIFICATION_ACCESS)
        assertEquals("Not granted", neededItem.status)
        assertTrue(neededItem.needsAttention)
        assertFalse(needed.ready)
    }

    @Test
    fun `notification permission is read from the platform result`() {
        val health = evaluate(notificationsGranted = false)

        val item = health.item(SetupHealthAction.NOTIFICATION_PERMISSION)
        assertEquals("Not granted", item.status)
        assertTrue(item.needsAttention)
        assertTrue(item.detail.contains("stop control"))
    }

    @Test
    fun `battery optimization and background mode can each block readiness`() {
        val optimized = evaluate(batteryIgnored = false)
        val battery = optimized.item(SetupHealthAction.BATTERY_OPTIMIZATION)
        assertTrue(battery.needsAttention)
        assertEquals("Optimized", battery.status)

        val backgroundOff = evaluate(
            state = readyState().copy(
                settings = readyState().settings.copy(keepInBackground = false),
            ),
        )
        val background = backgroundOff.item(SetupHealthAction.BACKGROUND_MODE)
        assertTrue(background.needsAttention)
        assertEquals("Off", background.status)
        assertTrue(backgroundOff.item(SetupHealthAction.BOOT_RESUME).needsAttention)
        assertEquals(2, backgroundOff.attentionCount)
        assertEquals("2 items need attention", backgroundOff.summary)
    }

    @Test
    fun `boot resume explains whether the toggle or the armed bike is missing`() {
        val toggleOff = evaluate(
            state = readyState().copy(
                settings = readyState().settings.copy(startOnBoot = false),
            ),
        )
        val toggleItem = toggleOff.item(SetupHealthAction.BOOT_RESUME)
        assertTrue(toggleItem.detail.contains("Turn on resume mirroring"))

        val notArmed = evaluate(
            state = readyState().copy(
                settings = readyState().settings.copy(armedVin = null),
            ),
        )
        val armedItem = notArmed.item(SetupHealthAction.BOOT_RESUME)
        assertTrue(armedItem.detail.contains("Select a bike"))
    }

    @Test
    fun `an unselected bike is called out by name when one is selected`() {
        val none = evaluate(state = readyState().copy(selected = null))
        val noneItem = none.item(SetupHealthAction.SELECTED_BIKE)
        assertEquals("None", noneItem.status)
        assertTrue(noneItem.needsAttention)

        val named = evaluate().item(SetupHealthAction.SELECTED_BIKE)
        assertEquals("Pulsar", named.status)
        assertFalse(named.needsAttention)
    }
}
