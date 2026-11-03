package dev.pulsarconnect.app.ble

import android.content.Context
import dev.pulsarconnect.core.ble.BikeDeviceStore
import dev.pulsarconnect.core.ble.BikeTarget

/**
 * App-private per-VIN selection. The address is intentionally not exported, logged or backed up;
 * it is only a local lookup key for auto-connect.
 */
class AndroidBikeDeviceStore(context: Context) : BikeDeviceStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun selected(vin: String): BikeTarget? {
        val id = preferences.getString(deviceKey(vin), null) ?: return null
        val name = preferences.getString(nameKey(vin), null)
        return BikeTarget(id = id, name = name, vin = vin)
    }

    override fun select(target: BikeTarget) {
        preferences.edit()
            .putString(deviceKey(target.vin), target.id)
            .putString(nameKey(target.vin), target.name)
            .apply()
    }

    override fun clear(vin: String) {
        preferences.edit()
            .remove(deviceKey(vin))
            .remove(nameKey(vin))
            .apply()
    }

    override fun autoConnect(vin: String): Boolean =
        preferences.getBoolean(autoConnectKey(vin), false)

    override fun setAutoConnect(vin: String, enabled: Boolean) {
        preferences.edit().putBoolean(autoConnectKey(vin), enabled).apply()
    }

    private fun deviceKey(vin: String) = "device.$vin.id"

    private fun nameKey(vin: String) = "device.$vin.name"

    private fun autoConnectKey(vin: String) = "autoconnect.$vin"

    private companion object {
        const val PREFERENCES_NAME = "bike_devices"
    }
}
