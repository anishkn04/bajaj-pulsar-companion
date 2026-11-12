package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.BikeLinkState
import dev.pulsarconnect.core.ble.ConnectionState

internal const val DEFAULT_BIKE_LINK_NAME = "Your bike"

private val macAddress = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
private val vinLike = Regex("(?i)\\b[A-HJ-NPR-Z0-9]{17}\\b")

/**
 * The service notification may show the advertised bike name, but never the VIN or Bluetooth
 * address. If a name looks like either identifier, fall back to a neutral label.
 */
internal fun bikeLinkDisplayName(name: String?): String {
    val candidate = name?.trim()?.takeIf(String::isNotEmpty) ?: return DEFAULT_BIKE_LINK_NAME
    return if (macAddress.containsMatchIn(candidate) || vinLike.containsMatchIn(candidate)) {
        DEFAULT_BIKE_LINK_NAME
    } else {
        candidate
    }
}

/** Human-readable link state for the ongoing notification. Never includes identifiers. */
internal fun bikeLinkNotificationState(state: BikeLinkState): String =
    when (val connection = state.connection) {
        ConnectionState.Idle -> "Not connected"
        ConnectionState.BluetoothUnavailable -> "Bluetooth is off"
        is ConnectionState.MissingPermissions -> "Bluetooth permission needed"
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Discovering -> "Reading bike services…"
        is ConnectionState.Connected -> when {
            !state.settings.mirroring -> "Connected · mirroring paused"
            state.mirroringActive -> "Connected · mirroring phone status"
            state.mirroringError != null -> "Connected · mirroring stopped"
            else -> "Connected · mirroring is off"
        }
        is ConnectionState.Reconnecting -> "Reconnecting…"
        is ConnectionState.WaitingForBike -> "Waiting for the bike to turn on"
        is ConnectionState.Failed -> "Link failed · open the app to retry"
    }
