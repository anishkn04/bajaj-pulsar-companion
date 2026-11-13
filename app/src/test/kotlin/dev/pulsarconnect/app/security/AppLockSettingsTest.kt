package dev.pulsarconnect.app.security

import android.app.Application
import android.content.Context
import androidx.biometric.BiometricManager
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
class AppLockSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(APP_LOCK_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `app lock is off by default`() {
        assertFalse(AppLockSettingsStore(context).load().requireBiometric)
    }

    @Test
    fun `app lock choice survives a new store instance`() {
        AppLockSettingsStore(context).save(AppLockSettings(requireBiometric = true))

        // A separate instance simulates the app restarting; sign-out never touches this store.
        assertTrue(AppLockSettingsStore(context).load().requireBiometric)
    }

    @Test
    fun `turning app lock off is remembered`() {
        val store = AppLockSettingsStore(context)
        store.save(AppLockSettings(requireBiometric = true))
        store.save(AppLockSettings(requireBiometric = false))

        assertFalse(store.load().requireBiometric)
    }

    @Test
    fun `biometric codes map to actionable states`() {
        assertEquals(
            BiometricStatus.AVAILABLE,
            BiometricStatus.fromCode(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertEquals(
            BiometricStatus.NOT_ENROLLED,
            BiometricStatus.fromCode(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricStatus.NO_HARDWARE,
            BiometricStatus.fromCode(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            BiometricStatus.TEMPORARILY_UNAVAILABLE,
            BiometricStatus.fromCode(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricStatus.TEMPORARILY_UNAVAILABLE,
            BiometricStatus.fromCode(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
        assertEquals(BiometricStatus.UNKNOWN, BiometricStatus.fromCode(Int.MIN_VALUE))
    }
}
