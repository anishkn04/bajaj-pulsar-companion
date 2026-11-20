package dev.pulsarconnect.core.ble

import java.util.UUID

/**
 * Encoder for the cluster's SMS/alert text endpoint.
 *
 * This is only a fallback for clusters without a writable TBT characteristic. The native frame
 * carries an alert type and a 32-byte sender field; it has no message body or maneuver glyph, so
 * navigation text is condensed into that field and the cluster presents it as an SMS alert. It is
 * never chosen when the dedicated navigation display is available.
 */
object AlertProtocol {
    val SERVICE_UUID: UUID = PhoneStatusProtocol.SERVICE_UUID
    val ALERT_UUID: UUID = UUID.fromString("0410676e-6972-6565-6e69-676e4543544f")

    const val FRAME_SIZE: Int = 44
    const val REQUIRED_MTU: Int = FRAME_SIZE + 3
    const val MAX_TEXT_BYTES: Int = 32

    private const val SMS_ALERT = 1

    /** True only when the peer exposes the writable ALERT endpoint. */
    fun isSupportedBy(services: List<BleService>): Boolean = services.any { service ->
        service.uuid == SERVICE_UUID && service.characteristics.any {
            it.uuid == ALERT_UUID && it.canWrite
        }
    }

    fun encode(rawText: String, epochSeconds: Long): ByteArray {
        val text = NavText.sanitize(rawText)
            .map { ch ->
                if (ch in ' '..'~') ch else ' '
            }
            .joinToString("")
            .trim()
            .take(MAX_TEXT_BYTES)
            .toByteArray(Charsets.US_ASCII)

        return ByteArray(FRAME_SIZE).apply {
            this[0] = SMS_ALERT.toByte()
            this[1] = text.size.toByte()
            text.copyInto(this, 2)
            val seconds = (epochSeconds and 0xFFFFFFFFL).toInt()
            this[34] = ((seconds ushr 24) and 0xFF).toByte()
            this[35] = ((seconds ushr 16) and 0xFF).toByte()
            this[36] = ((seconds ushr 8) and 0xFF).toByte()
            this[37] = (seconds and 0xFF).toByte()
        }
    }
}
