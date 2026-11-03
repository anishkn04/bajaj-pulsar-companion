package dev.pulsarconnect.core.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BikeDeviceStoreTest {
    private val store = InMemoryBikeDeviceStore()
    private val bike = testTarget()
    private val other = testTarget(id = "11:22:33:44:55:66", name = "Other", vin = "MD2ABCDEFGH654321")

    @Test
    fun `selection is isolated per vin`() {
        assertNull(store.selected(bike.vin))

        store.select(bike)
        store.select(other)

        assertEquals(bike, store.selected(bike.vin))
        assertEquals(other, store.selected(other.vin))
        assertNull(store.selected("MD2ABCDEFGH000000"))
    }

    @Test
    fun `selecting again replaces the stored device`() {
        store.select(bike)
        val replacement = bike.copy(id = "99:88:77:66:55:44", name = "New bike")
        store.select(replacement)

        assertEquals(replacement, store.selected(bike.vin))
    }

    @Test
    fun `auto connect defaults to disabled and is per vin`() {
        assertFalse(store.autoConnect(bike.vin))

        store.setAutoConnect(bike.vin, enabled = true)

        assertTrue(store.autoConnect(bike.vin))
        assertFalse(store.autoConnect(other.vin))
    }

    @Test
    fun `clear removes only the selected device`() {
        store.select(bike)
        store.select(other)
        store.setAutoConnect(bike.vin, enabled = true)

        store.clear(bike.vin)

        assertNull(store.selected(bike.vin))
        assertEquals(other, store.selected(other.vin))
        assertTrue(store.autoConnect(bike.vin))
    }
}
