package dev.pulsarconnect.app.ble

import android.Manifest
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidBikeDeviceStoreTest {

    private val store = AndroidBikeDeviceStore(RuntimeEnvironment.getApplication())

    @Test
    fun `selection and auto connect are isolated per vin`() {
        assertNull(store.selected("MD2ABCDEFGH123456"))
        assertFalse(store.autoConnect("MD2ABCDEFGH123456"))

        store.select(dev.pulsarconnect.core.ble.BikeTarget("AA:BB:CC:DD:EE:FF", "Pulsar", "MD2ABCDEFGH123456"))
        store.setAutoConnect("MD2ABCDEFGH123456", true)

        assertEquals("AA:BB:CC:DD:EE:FF", store.selected("MD2ABCDEFGH123456")?.id)
        assertTrue(store.autoConnect("MD2ABCDEFGH123456"))
        assertNull(store.selected("MD2ABCDEFGH654321"))
        assertFalse(store.autoConnect("MD2ABCDEFGH654321"))
    }

    @Test
    fun `clear removes the device but keeps the auto connect preference`() {
        store.select(dev.pulsarconnect.core.ble.BikeTarget("AA:BB:CC:DD:EE:FF", null, "MD2ABCDEFGH123456"))
        store.setAutoConnect("MD2ABCDEFGH123456", true)

        store.clear("MD2ABCDEFGH123456")

        assertNull(store.selected("MD2ABCDEFGH123456"))
        assertTrue(store.autoConnect("MD2ABCDEFGH123456"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidBlePermissionsTest {

    @Test
    fun `platform permission names match the sdk requirement`() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            AndroidBlePermissions.androidPermissions(31).toSet(),
        )
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            AndroidBlePermissions.androidPermissions(30).toSet(),
        )
    }

    @Test
    fun `missing permissions reflect the runtime grant state`() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN)

        assertEquals(
            setOf(dev.pulsarconnect.core.ble.BlePermission.CONNECT),
            AndroidBlePermissions.missing(context, 35),
        )
    }
}
