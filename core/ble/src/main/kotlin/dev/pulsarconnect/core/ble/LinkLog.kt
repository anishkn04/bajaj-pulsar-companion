package dev.pulsarconnect.core.ble

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LinkLogKind(val label: String) {
    TRANSITION("link"),
    FAILURE("failure"),
    SETTING("setting"),
    PASSIVE_WAIT("passive"),
}

data class LinkLogEntry(
    val timestampMillis: Long,
    val kind: LinkLogKind,
    val message: String,
)

/** What the background passive connection is doing right now. */
enum class PassiveWaitState {
    OFF,
    ARMED,
    WAITING,
    ATTACHED,
}

/**
 * A small in-memory ring buffer of link transitions and gating changes.
 *
 * The log intentionally stores descriptions rather than targets, VINs, or Bluetooth addresses.
 * Export applies a second redaction pass so a future message cannot leak an identifier.
 */
class LinkLog(
    private val capacity: Int = 100,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val buffer = ArrayDeque<LinkLogEntry>()
    private val _entries = MutableStateFlow<List<LinkLogEntry>>(emptyList())
    val entries: StateFlow<List<LinkLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun record(kind: LinkLogKind, message: String): LinkLogEntry {
        val entry = LinkLogEntry(clock(), kind, message)
        buffer.addLast(entry)
        while (buffer.size > capacity) buffer.removeFirst()
        _entries.value = buffer.toList()
        return entry
    }

    @Synchronized
    fun snapshot(): List<LinkLogEntry> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }
}

/** One-line description of a link state transition, without target identifiers. */
fun linkTransitionLogMessage(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Link idle"
    ConnectionState.BluetoothUnavailable -> "Bluetooth unavailable"
    is ConnectionState.MissingPermissions ->
        "Missing Bluetooth permission: ${state.missing.joinToString { it.name.lowercase() }}"
    is ConnectionState.Connecting -> "Connecting, attempt ${state.attempt + 1}"
    is ConnectionState.Discovering -> "Reading bike services"
    is ConnectionState.Connected ->
        "Connected, ${state.services.size} service${if (state.services.size == 1) "" else "s"}"
    is ConnectionState.Reconnecting -> "Reconnecting, attempt ${state.attempt + 1}"
    is ConnectionState.WaitingForBike -> "Waiting for the bike to turn on"
    is ConnectionState.Failed -> "Link failed: ${linkFailureLogMessage(state.reason)}"
}

/** Short failure summary safe for logs and notification surfaces. */
fun linkFailureLogMessage(reason: BleFailure): String = when (reason) {
    BleFailure.AdapterOff -> "Bluetooth is off"
    is BleFailure.PermissionDenied -> "Bluetooth permission denied"
    is BleFailure.ConnectTimeout -> "Connection timed out"
    is BleFailure.DiscoveryTimeout -> "Service discovery timed out"
    is BleFailure.DiscoveryFailed -> "Service discovery failed"
    BleFailure.NotResponding -> "Bike did not respond"
    is BleFailure.GattError -> "GATT error ${reason.operation} (${reason.status})"
    is BleFailure.MtuTooSmall -> "Bluetooth packet size too small"
    is BleFailure.Disconnected -> "Link disconnected (${reason.status})"
    is BleFailure.TooManyAttempts -> "Too many reconnect attempts (${reason.attempts})"
    is BleFailure.Unexpected -> "Unexpected link failure"
}

private val macAddress = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
private val vinLike = Regex("(?i)\\b[A-HJ-NPR-Z0-9]{17}\\b")

fun redactLinkLogText(text: String): String =
    text.replace(macAddress, "[redacted-mac]").replace(vinLike, "[redacted-vin]")

/** Exportable text for a bug report. Every line is timestamped and identifier-redacted. */
fun exportLinkLog(entries: List<LinkLogEntry>): String = buildString {
    appendLine("Pulsar Connect link log")
    appendLine()
    entries.forEach { entry ->
        val kind = entry.kind.label.uppercase()
        appendLine("${Instant.ofEpochMilli(entry.timestampMillis)} $kind: ${redactLinkLogText(entry.message)}")
    }
}
