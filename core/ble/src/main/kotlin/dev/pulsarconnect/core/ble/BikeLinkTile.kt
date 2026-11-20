package dev.pulsarconnect.core.ble

/** How the Quick Settings tile should be presented. */
enum class BikeLinkTileVisual { UNAVAILABLE, INACTIVE, ACTIVE }

/** What a tile tap should do. */
enum class BikeLinkTileAction { CONNECT, DISCONNECT, OPEN_APP }

/** Rider-facing reason for the tile's current appearance. */
enum class BikeLinkTileStatus {
    NO_BIKE,
    MIRRORING_OFF,
    BLUETOOTH_OFF,
    MISSING_PERMISSIONS,
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    WAITING_FOR_BIKE,
    DISCONNECTED,
    FAILED,
}

data class BikeLinkTileModel(
    val visual: BikeLinkTileVisual,
    val action: BikeLinkTileAction,
    val status: BikeLinkTileStatus,
)

/**
 * Maps the same link state the app shows to the Quick Settings tile.
 *
 * A tile tap may only connect while mirroring is already armed. When mirroring is off, there is
 * no bike, or Bluetooth/permissions need attention, the only action is to open the app, so the
 * tile can never start the background service behind a disarmed preference.
 */
fun bikeLinkTileModel(
    settings: BikeLinkSettings,
    connection: ConnectionState,
    hasArmedBike: Boolean,
): BikeLinkTileModel {
    if (!hasArmedBike) {
        return BikeLinkTileModel(BikeLinkTileVisual.UNAVAILABLE, BikeLinkTileAction.OPEN_APP, BikeLinkTileStatus.NO_BIKE)
    }
    if (!settings.mirroring) {
        return BikeLinkTileModel(
            BikeLinkTileVisual.INACTIVE,
            BikeLinkTileAction.OPEN_APP,
            BikeLinkTileStatus.MIRRORING_OFF,
        )
    }
    return when (connection) {
        is ConnectionState.Connected -> model(
            BikeLinkTileVisual.ACTIVE, BikeLinkTileAction.DISCONNECT, BikeLinkTileStatus.CONNECTED,
        )
        is ConnectionState.Connecting,
        is ConnectionState.Discovering,
        -> model(BikeLinkTileVisual.ACTIVE, BikeLinkTileAction.DISCONNECT, BikeLinkTileStatus.CONNECTING)

        is ConnectionState.Reconnecting ->
            model(BikeLinkTileVisual.ACTIVE, BikeLinkTileAction.DISCONNECT, BikeLinkTileStatus.RECONNECTING)

        is ConnectionState.WaitingForBike ->
            model(BikeLinkTileVisual.ACTIVE, BikeLinkTileAction.DISCONNECT, BikeLinkTileStatus.WAITING_FOR_BIKE)

        is ConnectionState.Failed -> when (connection.reason) {
            BleFailure.AdapterOff ->
                model(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.OPEN_APP, BikeLinkTileStatus.BLUETOOTH_OFF)
            is BleFailure.PermissionDenied ->
                model(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.OPEN_APP, BikeLinkTileStatus.MISSING_PERMISSIONS)
            else -> model(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.CONNECT, BikeLinkTileStatus.FAILED)
        }
        ConnectionState.BluetoothUnavailable ->
            model(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.OPEN_APP, BikeLinkTileStatus.BLUETOOTH_OFF)
        is ConnectionState.MissingPermissions ->
            model(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.OPEN_APP, BikeLinkTileStatus.MISSING_PERMISSIONS)
        ConnectionState.Idle ->
            model(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.CONNECT, BikeLinkTileStatus.DISCONNECTED)
    }
}

private fun model(
    visual: BikeLinkTileVisual,
    action: BikeLinkTileAction,
    status: BikeLinkTileStatus,
) = BikeLinkTileModel(visual, action, status)
