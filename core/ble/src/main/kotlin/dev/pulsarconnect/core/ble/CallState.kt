package dev.pulsarconnect.core.ble

/**
 * Call states from the native Android `CallState` enum.
 *
 * [wireValue] is the fourth constructor argument stored by the native enum, not its ordinal.
 * Evidence: `CallState.<clinit>` / `CallState.getValue` in the captured Android APK.
 */
enum class CallState(val wireValue: Int) {
    NO_CALL(0),
    INCOMING_CALL(1),
    OUTGOING_CALL(2),
    ACTIVE_CALL(3),
    END_CALL(4),
}

/**
 * A privacy-safe snapshot of what the cluster may show for the current call.
 *
 * There is deliberately no phone-number field. A caller that cannot be named is represented by
 * `null` and encoded as the generic label, never by a dialable value.
 */
data class CallSnapshot(
    val state: CallState = CallState.NO_CALL,
    val callerName: String? = null,
)

/** Phone-side call transitions the sender may mirror. */
sealed interface CallEvent {
    /** An incoming call is ringing. [callerName] is a display name only, never a number. */
    data class Incoming(val callerName: String?) : CallEvent

    /** The incoming call was answered. */
    data class Active(val callerName: String?) : CallEvent

    /** The call ended or stopped ringing without being answered. */
    data object Ended : CallEvent

    /** The dialer reported a missed call. The cluster shows only the fixed icon. */
    data object Missed : CallEvent
}

/** Opt-in switches that gate what [CallEvent] values are actually written to the cluster. */
data class CallMirrorOptions(
    val callerName: Boolean = false,
    val missedCalls: Boolean = false,
) {
    val anyEnabled: Boolean get() = callerName || missedCalls
}
