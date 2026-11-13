package dev.pulsarconnect.app.security

import android.content.Context
import androidx.biometric.BiometricManager

internal const val APP_LOCK_PREFERENCES_NAME = "app_lock"

/** Opt-in app lock, kept outside the sign-in session so it survives sign-out and restarts. */
data class AppLockSettings(
    val requireBiometric: Boolean = false,
)

/**
 * Whether this device can currently satisfy a biometric unlock. Enrollment and hardware can
 * change while the app is installed, so the locked screen re-checks this rather than trusting
 * the value captured when the rider enabled the lock.
 */
enum class BiometricStatus {
    AVAILABLE,
    NOT_ENROLLED,
    NO_HARDWARE,
    TEMPORARILY_UNAVAILABLE,
    UNKNOWN;

    companion object {
        fun fromCode(code: Int): BiometricStatus = when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            -> TEMPORARILY_UNAVAILABLE
            else -> UNKNOWN
        }
    }
}

fun checkBiometricStatus(context: Context): BiometricStatus = BiometricStatus.fromCode(
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK),
)

class AppLockSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(APP_LOCK_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AppLockSettings = AppLockSettings(
        requireBiometric = preferences.getBoolean(KEY_REQUIRE_BIOMETRIC, false),
    )

    fun save(settings: AppLockSettings) {
        preferences.edit()
            .putBoolean(KEY_REQUIRE_BIOMETRIC, settings.requireBiometric)
            .apply()
    }

    private companion object {
        const val KEY_REQUIRE_BIOMETRIC = "require_biometric"
    }
}
