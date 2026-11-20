package dev.pulsarconnect.core.ble

import java.util.UUID

/**
 * Encoder for the bike's dedicated turn-by-turn display.
 *
 * Layout mirrors the native `TbtNavFrame.buildNavHelperFrame` used by the official cluster link:
 * type/unit/PM flags, maneuver icon, 16.16 step distance, ETA fields, total distance, GPS status,
 * street text and a one-byte additive checksum. Only fields derivable from a navigation
 * notification are populated. The frame never carries a call state, so the cluster cannot show a
 * call or message icon for navigation.
 */
object TbtProtocol {
    val SERVICE_UUID: UUID = PhoneStatusProtocol.SERVICE_UUID
    val TBT_UUID: UUID = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f")

    const val FRAME_SIZE: Int = 48
    const val REQUIRED_MTU: Int = FRAME_SIZE + 3

    private const val STREET_OFFSET = 15
    private const val STREET_MAX_BYTES = 31
    private const val CHECKSUM_OFFSET = 47
    private const val NAV_UPDATE = 1
    private const val STEP_UNIT_METRES = 1 shl 4
    private const val ETA_PM = 1 shl 7
    private const val TOTAL_UNIT_METRES = 1
    private const val GPS_ACTIVE = 1 shl 2
    private const val BLINK_OFFSET = 32

    /** True only when the peer exposes the writable TBT endpoint. */
    fun isSupportedBy(services: List<BleService>): Boolean = services.any { service ->
        service.uuid == SERVICE_UUID && service.characteristics.any {
            it.uuid == TBT_UUID && it.canWrite
        }
    }

    /** A navigation update for [cue]. */
    fun encode(cue: NavCue): ByteArray = build(
        type = NAV_UPDATE,
        maneuver = iconFor(cue),
        step = encodeDistance(cue.distanceMeters ?: 0),
        total = encodeDistance(cue.totalDistanceMeters ?: 0),
        etaHour = cue.etaHour ?: 0,
        etaMinute = cue.etaMinute ?: 0,
        roundaboutExit = cue.roundaboutExit,
        etaPm = cue.etaPm,
        text = cue.text,
    )

    /** All-zero type frame with the same link flags as a native nav stop. */
    fun stopFrame(): ByteArray = build(
        type = 0,
        maneuver = 0,
        step = encodeDistance(0),
        total = encodeDistance(0),
        etaHour = 0,
        etaMinute = 0,
        roundaboutExit = 0,
        etaPm = false,
        text = "",
    )

    private data class EncodedDistance(val fixed: Int, val metres: Boolean)

    /**
     * Native format: below 1 km the integer holds metres and no fraction; otherwise it holds
     * kilometres with two decimal places (truncated, matching the native encoder).
     */
    private fun encodeDistance(meters: Int?): EncodedDistance {
        val value = meters?.coerceAtLeast(0) ?: 0
        if (value < 1_000) return EncodedDistance(fixed = value shl 16, metres = true)
        return EncodedDistance(
            fixed = ((value / 1_000) shl 16) or ((value % 1_000) / 10),
            metres = false,
        )
    }

    private fun iconFor(cue: NavCue): Int {
        val base = cue.maneuver.iconId
        val blink = cue.distanceMeters?.let { it in 0 until 100 } == true &&
            cue.maneuver != NavManeuver.DESTINATION
        return if (blink) base + BLINK_OFFSET else base
    }

    private fun build(
        type: Int,
        maneuver: Int,
        step: EncodedDistance,
        total: EncodedDistance,
        etaHour: Int,
        etaMinute: Int,
        roundaboutExit: Int,
        etaPm: Boolean,
        text: String,
    ): ByteArray {
        val frame = ByteArray(FRAME_SIZE)
        val flags = type or
            (if (step.metres) STEP_UNIT_METRES else 0) or
            (if (etaPm) ETA_PM else 0)
        frame[0] = flags.toByte()
        frame[1] = maneuver.toByte()
        writeFixedLittleEndian(frame, 2, step.fixed)
        frame[6] = (etaMinute and 0xFF).toByte()
        frame[7] = ((etaHour and 0xFF) or ((roundaboutExit and 0x0F) shl 4)).toByte()
        writeFixedLittleEndian(frame, 8, total.fixed)
        frame[12] = ((if (total.metres) TOTAL_UNIT_METRES else 0) or GPS_ACTIVE).toByte()
        val street = streetBytes(text)
        frame[14] = street.size.toByte()
        street.copyInto(frame, STREET_OFFSET)
        frame[CHECKSUM_OFFSET] = (0 until CHECKSUM_OFFSET).sumOf { frame[it].toInt() and 0xFF }.toByte()
        return frame
    }

    private fun writeFixedLittleEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** The cluster's street field accepts ASCII alphanumerics, spaces and dots. */
    private fun streetBytes(raw: String): ByteArray {
        val sanitized = NavText.sanitize(raw).map { ch ->
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == ' ' || ch == '.') ch else ' '
        }.joinToString("").trim().take(STREET_MAX_BYTES)
        return sanitized.toByteArray(Charsets.US_ASCII)
    }
}
