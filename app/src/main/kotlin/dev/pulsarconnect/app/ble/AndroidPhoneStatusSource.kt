package dev.pulsarconnect.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import dev.pulsarconnect.core.ble.PhoneStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android battery/signal source.
 *
 * Signal strength is optional: without READ_PHONE_STATE (or without telephony) the flow
 * still reports battery and leaves the signal unknown. The captured protocol refreshes
 * battery every five seconds, so this source uses the same cadence.
 */
class AndroidPhoneStatusSource(context: Context) : PhoneStatusSource {

    private val appContext: Context = context.applicationContext

    @Suppress("DEPRECATION")
    override fun statuses(): Flow<PhoneStatus> = callbackFlow {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        val battery = appContext.getSystemService(BatteryManager::class.java)

        var signalLevel: Int? = null
        var listener: PhoneStateListener? = null

        fun hasPhonePermission(): Boolean =
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

        fun snapshot(): PhoneStatus = PhoneStatus(
            batteryPercent = battery
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 },
            signalLevel = signalLevel,
        )

        fun unregister() {
            val current = listener ?: return
            listener = null
            try {
                telephony?.listen(current, PhoneStateListener.LISTEN_NONE)
            } catch (_: SecurityException) {
                // Permission can disappear between the check and the platform call.
            }
        }

        val stateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                signalLevel = signalStrength.level.takeIf { it in 0..4 }
                trySend(snapshot())
            }
        }

        fun register() {
            if (telephony == null || !hasPhonePermission()) {
                signalLevel = null
                unregister()
                return
            }
            if (listener != null) return
            try {
                telephony.listen(stateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                listener = stateListener
            } catch (_: SecurityException) {
                signalLevel = null
                listener = null
            }
        }

        trySend(snapshot())
        register()

        val refresh = launch {
            while (isActive) {
                delay(BATTERY_REFRESH_MILLIS)
                // Re-check both permission and registration so revocation and late grants work.
                if (telephony != null && hasPhonePermission()) {
                    register()
                } else {
                    signalLevel = null
                    unregister()
                }
                trySend(snapshot())
            }
        }

        awaitClose {
            refresh.cancel()
            unregister()
        }
    }

    private companion object {
        const val BATTERY_REFRESH_MILLIS = 5_000L
    }
}
