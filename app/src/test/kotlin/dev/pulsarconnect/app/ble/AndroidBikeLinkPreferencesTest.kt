package dev.pulsarconnect.app.ble

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
class AndroidBikeLinkPreferencesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `mirroring background and boot resume start enabled`() {
        val settings = AndroidBikeLinkPreferences(context).settings.value

        assertTrue(settings.mirroring)
        assertTrue(settings.keepInBackground)
        assertTrue(settings.startOnBoot)
        assertNull(settings.armedVin)
        assertTrue(settings.runsInBackground)
        assertFalse(settings.resumesAfterBoot)
    }

    @Test
    fun `settings survive a new instance`() {
        val first = AndroidBikeLinkPreferences(context)
        first.setMirroring(false)
        first.setKeepInBackground(false)
        first.setStartOnBoot(false)
        first.setArmedVin("MD2ABCDEFGH123456")

        val reloaded = AndroidBikeLinkPreferences(context).settings.value

        assertFalse(reloaded.mirroring)
        assertFalse(reloaded.keepInBackground)
        assertFalse(reloaded.startOnBoot)
        assertEquals("MD2ABCDEFGH123456", reloaded.armedVin)
    }

    @Test
    fun `call mirroring starts off`() {
        val settings = AndroidBikeLinkPreferences(context).settings.value

        assertFalse(settings.mirrorCallerName)
        assertFalse(settings.mirrorMissedCalls)
        assertFalse(settings.mirrorsAnyCall)
    }

    @Test
    fun `call toggles survive a new instance independently`() {
        val first = AndroidBikeLinkPreferences(context)
        first.setMirrorCallerName(true)

        val reloaded = AndroidBikeLinkPreferences(context).settings.value
        assertTrue(reloaded.mirrorCallerName)
        assertFalse(reloaded.mirrorMissedCalls)

        first.setMirrorCallerName(false)
        first.setMirrorMissedCalls(true)
        val second = AndroidBikeLinkPreferences(context).settings.value
        assertFalse(second.mirrorCallerName)
        assertTrue(second.mirrorMissedCalls)
        assertTrue(second.mirrorsAnyCall)
        assertTrue(second.mirroring)
    }

    @Test
    fun `each setter updates the snapshot without losing sibling flags`() {
        val preferences = AndroidBikeLinkPreferences(context)

        preferences.setMirroring(false)
        assertFalse(preferences.settings.value.mirroring)
        assertTrue(preferences.settings.value.keepInBackground)
        assertTrue(preferences.settings.value.startOnBoot)

        preferences.setArmedVin("MD2ABCDEFGH123456")
        preferences.setArmedVin(null)
        assertNull(preferences.settings.value.armedVin)
        assertTrue(preferences.settings.value.keepInBackground)
    }
}
