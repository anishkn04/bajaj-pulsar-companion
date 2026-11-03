package dev.pulsarconnect.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeLinkPreferencesTest {

    @Test
    fun `mirroring and background mode are on by default`() = runTest {
        val settings = InMemoryBikeLinkPreferences().settings.value

        assertTrue(settings.mirroring)
        assertTrue(settings.keepInBackground)
        assertTrue(settings.startOnBoot)
        assertNull(settings.armedVin)
        assertTrue(settings.runsInBackground)
        assertFalse(settings.resumesAfterBoot)
    }

    @Test
    fun `background link requires mirroring and background mode together`() = runTest {
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = false, keepInBackground = true, startOnBoot = false),
        )

        assertFalse(preferences.settings.value.runsInBackground)

        preferences.setMirroring(true)
        assertTrue(preferences.settings.value.runsInBackground)

        preferences.setKeepInBackground(false)
        assertFalse(preferences.settings.value.runsInBackground)
    }

    @Test
    fun `boot resume requires an armed vin and a background link`() = runTest {
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = true, startOnBoot = true),
        )

        assertFalse(preferences.settings.value.resumesAfterBoot)

        preferences.setArmedVin("MD2ABCDEFGH123456")
        assertTrue(preferences.settings.value.resumesAfterBoot)

        preferences.setKeepInBackground(false)
        assertFalse(preferences.settings.value.resumesAfterBoot)
    }

    @Test
    fun `settings are exposed as one snapshot`() = runTest {
        val preferences = InMemoryBikeLinkPreferences()

        preferences.setMirroring(true)
        preferences.setKeepInBackground(true)
        preferences.setStartOnBoot(true)
        preferences.setArmedVin("MD2ABCDEFGH123456")

        assertEquals(
            BikeLinkSettings(
                mirroring = true,
                keepInBackground = true,
                startOnBoot = true,
                armedVin = "MD2ABCDEFGH123456",
            ),
            preferences.settings.value,
        )
    }

    @Test
    fun `armed vin can be cleared without touching the other flags`() = runTest {
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(
                mirroring = true,
                keepInBackground = true,
                startOnBoot = true,
                armedVin = "MD2ABCDEFGH123456",
            ),
        )

        preferences.setArmedVin(null)

        assertNull(preferences.settings.value.armedVin)
        assertTrue(preferences.settings.value.mirroring)
        assertTrue(preferences.settings.value.keepInBackground)
        assertTrue(preferences.settings.value.startOnBoot)
    }
}
