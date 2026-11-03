package dev.pulsarconnect.app.ble

import android.content.Context
import android.content.SharedPreferences
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeLinkSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent bike-link intent. These are global preferences: they describe how the rider wants
 * the phone to behave, not which bike it talks to.
 *
 * [armedVin] is only a lookup key for finding the selected bike at boot; it is never a credential
 * and never decrypts the session.
 */
class AndroidBikeLinkPreferences(context: Context) : BikeLinkPreferences {

    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val snapshot = dev.pulsarconnect.core.ble.BikeLinkSettings(
        mirroring = true,
        keepInBackground = true,
        startOnBoot = true,
    )

    private val settingsFlow: kotlinx.coroutines.flow.MutableStateFlow<BikeLinkSettings> =
        kotlinx.coroutines.flow.MutableStateFlow(read())

    override val settings: kotlinx.coroutines.flow.StateFlow<BikeLinkSettings>
        get() = settingsFlow.asStateFlow()

    init {
        apply(read())
    }

    private fun read(): BikeLinkSettings = BikeLinkSettings(
        mirroring = preferences.getBoolean(KEY_MIRRORING, true),
        keepInBackground = preferences.getBoolean(KEY_BACKGROUND, true),
        startOnBoot = preferences.getBoolean(KEY_BOOT, true),
        armedVin = preferences.getString(KEY_ARMED_VIN, null),
    )

    private fun apply(next: BikeLinkSettings) {
        preferences.edit()
            .putBoolean(KEY_MIRRORING, next.mirroring)
            .putBoolean(KEY_BACKGROUND, next.keepInBackground)
            .putBoolean(KEY_BOOT, next.startOnBoot)
            .putString(KEY_ARMED_VIN, next.armedVin)
            .apply()
        settingsFlow.value = next
    }

    override fun setMirroring(enabled: Boolean) {
        apply(settingsFlow.value.copy(mirroring = enabled))
    }

    override fun setKeepInBackground(enabled: Boolean) {
        apply(settingsFlow.value.copy(keepInBackground = enabled))
    }

    override fun setStartOnBoot(enabled: Boolean) {
        apply(settingsFlow.value.copy(startOnBoot = enabled))
    }

    override fun setArmedVin(vin: String?) {
        apply(settingsFlow.value.copy(armedVin = vin))
    }

    private companion object {
        const val PREFERENCES_NAME = "bike_link"

        private const val KEY_MIRRORING = "mirroring"
        private const val KEY_BACKGROUND = "keep_in_background"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_ARMED_VIN = "armed_vin"
    }
}
