package dev.pulsarconnect.core.ble

import java.util.UUID

/**
 * Read-only snapshot of the cluster's CONTROL characteristic.
 *
 * The official app treats most fields as counters: it acts only when a value differs from the
 * previous poll. This app deliberately performs no actions, so the raw counters are exposed for
 * diagnostics until each one has been observed on a live cluster.
 */
data class ClusterControlFrame(
    val volume: Int = 0,
    val callAccept: Int = 0,
    val callReject: Int = 0,
    val callRejectWithSms: Int = 0,
    val playlistPage: Int = 0,
    val takeMeHome: Int = 0,
    val resume: Int = 0,
    val pause: Int = 0,
    val next: Int = 0,
    val previous: Int = 0,
    val stop: Int = 0,
    val missedCallRead: Int = 0,
    val alertRead: Int = 0,
    val launchMediaPlayer: Int = 0,
)

data class ClusterControlState(
    val frame: ClusterControlFrame? = null,
    val polling: Boolean = false,
)

/**
 * CONTROL (`0a1...`) is a read-only command/ack channel, not telemetry. The bike never
 * notifies on it; the phone has to poll. All-zero responses are the idle state observed on
 * the captured cluster.
 */
object ControlProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
    val CONTROL_UUID: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")

    /** The native parser ignores responses shorter than this. */
    const val MIN_FRAME_SIZE: Int = 20

    /** Observed read response length. */
    const val RESPONSE_SIZE: Int = 26

    /** The official app schedules a read every `0x2bc` milliseconds. */
    const val POLL_INTERVAL_MILLIS: Long = 700

    fun isSupportedBy(services: List<BleService>): Boolean = services.any { service ->
        service.uuid == SERVICE_UUID && service.characteristics.any {
            it.uuid == CONTROL_UUID && it.canRead
        }
    }

    fun parse(bytes: ByteArray): ClusterControlFrame? {
        if (bytes.size < MIN_FRAME_SIZE) return null
        fun byte(index: Int): Int = bytes[index].toInt() and 0xff
        return ClusterControlFrame(
            volume = byte(0) and 0x0f,
            callAccept = byte(1),
            callReject = byte(2),
            callRejectWithSms = byte(3),
            playlistPage = byte(4) and 0x3f,
            takeMeHome = byte(5),
            resume = byte(6),
            pause = byte(7),
            next = byte(8),
            previous = byte(9),
            stop = byte(10),
            missedCallRead = byte(11),
            alertRead = byte(12),
            launchMediaPlayer = byte(13),
        )
    }
}
