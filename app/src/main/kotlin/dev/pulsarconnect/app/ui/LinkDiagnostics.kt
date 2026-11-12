package dev.pulsarconnect.app.ui

import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.LinkLogEntry
import dev.pulsarconnect.core.ble.LinkLogKind
import dev.pulsarconnect.core.ble.PassiveWaitState
import dev.pulsarconnect.core.ble.linkFailureLogMessage

/** How many recent events the diagnostics card shows before the rider opens the full export. */
internal const val LINK_LOG_RECENT_LIMIT = 8

internal data class LinkDiagnostics(
    val blocker: String,
    val background: String,
    val boot: String,
    val passiveWait: String,
    val lastTransition: LinkLogEntry?,
    val lastFailure: LinkLogEntry?,
    val recent: List<LinkLogEntry>,
)

/**
 * Answers "what is stopping the link" from the same live state the link itself uses.
 *
 * Nothing here is cached: permissions, adapter state, connection state, settings and passive-wait
 * state all arrive from the platform/controller snapshot.
 */
internal fun linkDiagnostics(state: BleUiState): LinkDiagnostics {
    val log = state.linkLog
    return LinkDiagnostics(
        blocker = linkBlocker(state),
        background = backgroundGating(state.settings),
        boot = bootGating(state.settings),
        passiveWait = passiveWaitLabel(state.passiveWait),
        lastTransition = log.lastOrNull { it.kind == LinkLogKind.TRANSITION },
        lastFailure = log.lastOrNull { it.kind == LinkLogKind.FAILURE },
        recent = log.asReversed().take(LINK_LOG_RECENT_LIMIT),
    )
}

internal fun linkBlocker(state: BleUiState): String {
    val connection = state.connection
    if (state.missingPermissions.isNotEmpty() || connection is ConnectionState.MissingPermissions) {
        return "Bluetooth permission is missing. Grant nearby-device access."
    }
    if (state.adapterState == AdapterState.OFF || connection is ConnectionState.BluetoothUnavailable) {
        return "Bluetooth is turned off. Turn it on to reach the bike."
    }
    if (state.selected == null) {
        return "No bike is selected. Scan for your bike and choose its link."
    }
    return when (connection) {
        is ConnectionState.Connected -> when {
            !state.settings.mirroring ->
                "Mirroring is paused. Turn it on to send phone status."
            state.phoneStatusError != null ->
                "Connected, but phone-status mirroring stopped. Reconnect to try again."
            state.phoneStatusSending ->
                "Connected and mirroring phone status."
            else ->
                "Connected, but phone status has not been sent yet."
        }
        is ConnectionState.Failed ->
            "Last attempt failed: ${linkFailureLogMessage(connection.reason)}."
        is ConnectionState.WaitingForBike ->
            "Waiting for the bike to turn on. Android will attach by itself when it advertises."
        is ConnectionState.Connecting,
        is ConnectionState.Discovering,
        is ConnectionState.Reconnecting,
        -> "Connecting to the bike…"
        is ConnectionState.MissingPermissions ->
            "Bluetooth permission is missing. Grant nearby-device access."
        is ConnectionState.BluetoothUnavailable ->
            "Bluetooth is turned off. Turn it on to reach the bike."
        ConnectionState.Idle ->
            if (state.settings.mirroring) {
                "Mirroring is armed, but the link has not connected yet."
            } else {
                "Mirroring is off. Turn it on to connect to the bike."
            }
    }
}

internal fun backgroundGating(settings: BikeLinkSettings): String = when {
    settings.runsInBackground -> "On"
    !settings.mirroring -> "Off — mirroring is paused"
    !settings.keepInBackground -> "Off — keep in background is disabled"
    else -> "Off"
}

internal fun bootGating(settings: BikeLinkSettings): String = when {
    settings.resumesAfterBoot -> "On"
    !settings.startOnBoot -> "Off — resume after reboot is disabled"
    !settings.runsInBackground -> "Off — background mirroring is off"
    settings.armedVin == null -> "Off — no bike is armed"
    else -> "Off"
}

internal fun passiveWaitLabel(state: PassiveWaitState): String = when (state) {
    PassiveWaitState.OFF -> "Off"
    PassiveWaitState.ARMED -> "Armed"
    PassiveWaitState.WAITING -> "Waiting for bike"
    PassiveWaitState.ATTACHED -> "Attached"
}
