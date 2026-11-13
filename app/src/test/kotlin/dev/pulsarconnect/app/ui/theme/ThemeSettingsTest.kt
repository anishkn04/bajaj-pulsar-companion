package dev.pulsarconnect.app.ui.theme

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class ThemeSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(THEME_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `defaults to follow system with dynamic color enabled`() {
        val store = ThemeSettingsStore(context)

        assertEquals(ThemeSettings(mode = ThemeMode.SYSTEM, dynamicColor = true), store.load())
    }

    @Test
    fun `chosen mode and dynamic color survive a new store instance`() {
        ThemeSettingsStore(context).save(ThemeSettings(mode = ThemeMode.LIGHT, dynamicColor = false))

        // A separate instance simulates the app restarting and reading preferences again.
        assertEquals(
            ThemeSettings(mode = ThemeMode.LIGHT, dynamicColor = false),
            ThemeSettingsStore(context).load(),
        )
    }

    @Test
    fun `unknown stored mode falls back to following the system`() {
        context.getSharedPreferences(THEME_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", "SEPIA")
            .commit()

        assertEquals(ThemeMode.SYSTEM, ThemeSettingsStore(context).load().mode)
    }

    @Test
    fun `mode resolution honours the explicit choice over the system setting`() {
        assertTrue(resolvesToDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolvesToDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
        assertFalse(resolvesToDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertTrue(resolvesToDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test
    fun `dynamic color needs both the preference and Android 12 or newer`() {
        val enabled = ThemeSettings(dynamicColor = true)
        val disabled = ThemeSettings(dynamicColor = false)

        assertFalse(supportsDynamicColor(enabled, sdkInt = 30))
        assertTrue(supportsDynamicColor(enabled, sdkInt = 31))
        assertTrue(supportsDynamicColor(enabled, sdkInt = 35))
        assertFalse(supportsDynamicColor(disabled, sdkInt = 35))
    }
}
