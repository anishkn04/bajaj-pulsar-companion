package dev.pulsarconnect.core.ble

import java.util.UUID

/** Minimal idle subset of the native Android GENERAL encoder, not the iOS JS encoder. */
object PhoneStatusProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
    val GENERAL_UUID: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
    const val FRAME_SIZE: Int = 55

    /** ATT payload is MTU minus the three-byte notification/write header. */
    const val REQUIRED_MTU: Int = FRAME_SIZE + 3

    /** MTU observed on the verified Android capture. */
    const val PREFERRED_MTU: Int = 146

    // Verified default status: no headset and volume 5. The 0xc0 bits' meaning is unknown,
    // so this is not a magic/sync header. Evidence: CallFrame.getStatusByteZero, GlobalVar.<clinit>.
    private const val DEFAULT_STATUS: Int = 0xc0 or 5
    private const val NO_CALL: Int = 0

    /** True only when the peer exposes the writable GENERAL endpoint used by this frame. */
    fun isSupportedBy(services: List<BleService>): Boolean = services.any { service ->
        service.uuid == SERVICE_UUID && service.characteristics.any {
            it.uuid == GENERAL_UUID && it.canWrite
        }
    }

    /**
     * Unavailable battery is deliberately level 0, indistinguishable from a battery below 20%.
     * Unavailable signal likewise uses 0. Neither fallback claims a measured reading.
     * The caller owns heartbeat progression/wrapping; this encoder accepts only a wire byte.
     * Caller fields stay empty and byte 54 stays zero: native Android adds no checksum.
     */
    fun encodeIdle(status: PhoneStatus, heartbeat: Int): ByteArray {
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
            this[1] = ((batteryLevel shl 3) or NO_CALL).toByte()
            this[2] = (status.signalLevel ?: 0).toByte()
            this[4] = 1
            this[53] = heartbeat.toByte()
        }
    }
}
