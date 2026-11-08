package dev.pulsarconnect.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import dev.pulsarconnect.app.calls.CallNotificationBus
import dev.pulsarconnect.app.calls.CallNotificationSignal
import dev.pulsarconnect.core.ble.CallEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Call transitions from the platform call state plus the dialer notification label.
 *
 * The platform reports RINGING only for an incoming call, so outgoing calls are never mirrored
 * as incoming. The dialer notification supplies the display name; its title is discarded when
 * it is a phone number or a generic label. Missed calls come from the dialer notification and
 * carry no name at all.
 *
 * Requires READ_PHONE_STATE for ringing/active transitions. Without it, caller-name mirroring is
 * unavailable (the UI disables the switch); missed-call detection still works because it is
 * driven by the notification category alone.
 *
 * The platform listener runs on the main thread while the notification bus is collected on the
 * link scope, so the shared call state uses atomics.
 */
class AndroidPhoneCallSource(
    context: Context,
    private val bus: CallNotificationBus,
) : PhoneCallSource {

    private val appContext: Context = context.applicationContext

    @Suppress("DEPRECATION")
    override fun events(): Flow<CallEvent> = callbackFlow {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        val callerName = AtomicReference<String?>(null)
        val ringing = AtomicBoolean(false)
        val active = AtomicBoolean(false)
        val registered = AtomicReference<PhoneStateListener?>(null)

        fun hasPhonePermission(): Boolean =
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

        fun resetCall() {
            ringing.set(false)
            active.set(false)
            callerName.set(null)
        }

        val stateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                // phoneNumber is deliberately ignored: it is never read, stored or forwarded.
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        ringing.set(true)
                        active.set(false)
                        trySend(CallEvent.Incoming(callerName.get()))
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (ringing.getAndSet(false)) active.set(true)
                        if (active.get()) trySend(CallEvent.Active(callerName.get()))
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        val wasInCall = ringing.getAndSet(false) or active.getAndSet(false)
                        if (wasInCall) trySend(CallEvent.Ended)
                        callerName.set(null)
                    }
                }
            }
        }

        fun unregister() {
            val current = registered.getAndSet(null) ?: return
            try {
                telephony?.listen(current, PhoneStateListener.LISTEN_NONE)
            } catch (_: SecurityException) {
                // Permission can disappear between the check and the platform call.
            }
        }

        fun register() {
            if (telephony == null || !hasPhonePermission()) {
                unregister()
                return
            }
            if (registered.get() != null) return
            try {
                telephony.listen(stateListener, PhoneStateListener.LISTEN_CALL_STATE)
                registered.set(stateListener)
            } catch (_: SecurityException) {
                registered.set(null)
            }
        }

        val busJob = launch {
            bus.signals.collect { signal ->
                when (signal) {
                    is CallNotificationSignal.CallerName -> {
                        callerName.set(signal.name)
                        // Without call-state permission the ringing boundary is unknown, and
                        // guessing would risk mirroring an outgoing call. Stay silent.
                        if (!hasPhonePermission()) return@collect
                        when {
                            ringing.get() -> trySend(CallEvent.Incoming(signal.name))
                            active.get() -> trySend(CallEvent.Active(signal.name))
                            else -> Unit
                        }
                    }
                    CallNotificationSignal.Missed -> {
                        trySend(CallEvent.Missed)
                        resetCall()
                    }
                }
            }
        }

        register()

        // Permission can be granted or revoked while the link is running. Re-check both so the
        // listener follows the current state without leaking stale caller text.
        val permissionRefresh = launch {
            while (isActive) {
                delay(PERMISSION_REFRESH_MILLIS)
                if (telephony != null && hasPhonePermission()) {
                    register()
                } else {
                    if (registered.get() != null) resetCall()
                    unregister()
                }
            }
        }

        awaitClose {
            busJob.cancel()
            permissionRefresh.cancel()
            unregister()
        }
    }

    private companion object {
        const val PERMISSION_REFRESH_MILLIS = 5_000L
    }
}
