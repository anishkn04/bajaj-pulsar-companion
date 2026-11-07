package dev.pulsarconnect.core.ble

import java.util.UUID

/**
 * Native Android phone-status encoders, not the iOS JavaScript encoder.
 *
 * GENERAL carries battery/signal and the optional caller name. MISS_CALL is a separate fixed
 * frame; the official cluster renders only an icon for it, so this app sends the trigger byte and
 * no personal fields.
 */
object PhoneStatusProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
    val GENERAL_UUID: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
    val MISSED_CALL_UUID: UUID = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f")

    const val FRAME_SIZE: Int = 55
    const val MISSED_CALL_FRAME_SIZE: Int = 74

    /** ATT payload is MTU minus the three-byte notification/write header. */
    const val REQUIRED_MTU: Int = FRAME_SIZE + 3
    const val MISSED_CALL_REQUIRED_MTU: Int = MISSED_CALL_FRAME_SIZE + 3

    /** MTU observed on the verified Android capture. */
    const val PREFERRED_MTU: Int = 146

    // Caller text: length byte at 20, first 30 UTF-8 bytes at 21-50.
    const val CALLER_LENGTH_OFFSET: Int = 20
    const val CALLER_TEXT_OFFSET: Int = 21

    // MISS_CALL: native byte 0 is the constant trigger; the cluster shows only an icon.
    const val MISSED_CALL_TRIGGER_OFFSET: Int = 0
    const val MISSED_CALL_TRIGGER: Int = 1

    // Verified default status: no headset and volume 5. The 0xc0 bits' meaning is unknown,
    // so this is not a magic/sync header. Evidence: CallFrame.getStatusByteZero, GlobalVar.<clinit>.
    private const val DEFAULT_STATUS: Int = 0xc0 or 5

    /** True only when the peer exposes the writable GENERAL endpoint used by this frame. */
    fun isSupportedBy(services: List<BleService>): Boolean = services.any { service ->
        service.uuid == SERVICE_UUID && service.characteristics.any {
            it.uuid == GENERAL_UUID && it.canWrite
        }
    }

    /** True only when the peer exposes the writable MISS_CALL endpoint. */
    fun isMissedCallSupportedBy(services: List<BleService>): Boolean = services.any { service ->
        service.uuid == SERVICE_UUID && service.characteristics.any {
            it.uuid == MISSED_CALL_UUID && it.canWrite
        }
    }

    /**
     * Idle GENERAL frame: no call state and no caller text.
     *
     * Unavailable battery is deliberately level 0, indistinguishable from a battery below 20%.
     * Unavailable signal likewise uses 0. Neither fallback claims a measured reading.
     */
    fun encodeIdle(status: PhoneStatus, heartbeat: Int): ByteArray =
        encode(status, heartbeat, CallState.NO_CALL, callerName = null)

    /**
     * GENERAL frame with the native call fields.
     *
     * Byte 1 carries the call state in its low three bits. Only [CallState.ACTIVE_CALL] flips
     * bytes 3/4, matching the native predicates. Caller text is written only for
     * [CallState.INCOMING_CALL] and [CallState.ACTIVE_CALL]; unknown or phone-like names become
     * [CallerName.UNKNOWN_LABEL] so a dialable value can never reach the wire.
     *
     * The caller owns heartbeat progression/wrapping; this encoder accepts only a wire byte.
     * Byte 54 stays zero because the native Android encoder adds no checksum.
     */
    fun encode(
        status: PhoneStatus,
        heartbeat: Int,
        callState: CallState = CallState.NO_CALL,
        callerName: String? = null,
    ): ByteArray {
        require(heartbeat in 0..255) { "Heartbeat must be in 0..255" }
        val battery = status.batteryPercent ?: 0
        val batteryLevel = when {
            battery < 20 -> 0
            battery < 40 -> 1
            battery < 60 -> 2
            battery < 80 -> 3
            else -> 4
        }
        return ByteArray(FRAME_SIZE).apply {
            this[0] = DEFAULT_STATUS.toByte()
            this[1] = ((batteryLevel shl 3) or callState.wireValue).toByte()
            this[2] = (status.signalLevel ?: 0).toByte()
            this[3] = if (callState == CallState.ACTIVE_CALL) 1 else 0
            this[4] = if (callState == CallState.ACTIVE_CALL) 0 else 1
            if (callState == CallState.INCOMING_CALL || callState == CallState.ACTIVE_CALL) {
                val text = CallerName.wireBytes(callerName)
                this[CALLER_LENGTH_OFFSET] = text.size.toByte()
                text.copyInto(this, CALLER_TEXT_OFFSET)
            }
            this[53] = heartbeat.toByte()
        }
    }

    /**
     * Fixed MISS_CALL frame. The official cluster shows only an icon, so the name/number areas
     * stay zero rather than carrying contact data.
     */
    fun encodeMissedCall(): ByteArray = ByteArray(MISSED_CALL_FRAME_SIZE).apply {
        this[MISSED_CALL_TRIGGER_OFFSET] = MISSED_CALL_TRIGGER.toByte()
    }
}
