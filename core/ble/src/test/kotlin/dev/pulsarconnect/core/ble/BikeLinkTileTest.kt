package dev.pulsarconnect.core.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BikeLinkTileTest {
    private val vin = "MD2ABCDEFGH123456"
    private val target = testTarget(vin = vin)
    private val armed = BikeLinkSettings(mirroring = true, armedVin = vin)

    private fun model(
        connection: ConnectionState,
        settings: BikeLinkSettings = armed,
        hasArmedBike: Boolean = true,
    ) = bikeLinkTileModel(settings, connection, hasArmedBike)

    @Test
    fun `an unarmed or missing bike only opens the app`() {
        assertEquals(
            BikeLinkTileModel(BikeLinkTileVisual.UNAVAILABLE, BikeLinkTileAction.OPEN_APP, BikeLinkTileStatus.NO_BIKE),
            model(ConnectionState.Idle, hasArmedBike = false),
        )
        assertEquals(
            BikeLinkTileStatus.NO_BIKE,
            model(ConnectionState.Idle, settings = BikeLinkSettings(mirroring = true, armedVin = null), hasArmedBike = false).status,
        )
    }

    @Test
    fun `mirroring off never offers a connect that could start the service`() {
        val result = model(
            ConnectionState.Idle,
            settings = armed.copy(mirroring = false),
        )

        assertEquals(BikeLinkTileVisual.INACTIVE, result.visual)
        assertEquals(BikeLinkTileAction.OPEN_APP, result.action)
        assertEquals(BikeLinkTileStatus.MIRRORING_OFF, result.status)
    }

    @Test
    fun `an idle armed link offers connect`() {
        assertEquals(
            BikeLinkTileModel(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.CONNECT, BikeLinkTileStatus.DISCONNECTED),
            model(ConnectionState.Idle),
        )
    }

    @Test
    fun `active link states offer disconnect`() {
        for (state in listOf(
            ConnectionState.Connecting(target, attempt = 1),
            ConnectionState.Discovering(target),
            ConnectionState.Reconnecting(target, attempt = 2, retryAtMillis = 3),
            ConnectionState.WaitingForBike(target),
        )) {
            val result = model(state)
            assertEquals(BikeLinkTileVisual.ACTIVE, result.visual)
            assertEquals(BikeLinkTileAction.DISCONNECT, result.action)
        }
    }

    @Test
    fun `a connected link is active and disconnects`() {
        assertEquals(
            BikeLinkTileModel(BikeLinkTileVisual.ACTIVE, BikeLinkTileAction.DISCONNECT, BikeLinkTileStatus.CONNECTED),
            model(ConnectionState.Connected(target, services = emptyList(), sinceMillis = 0)),
        )
    }

    @Test
    fun `a generic failure offers a retry and keeps the tile available`() {
        assertEquals(
            BikeLinkTileModel(BikeLinkTileVisual.INACTIVE, BikeLinkTileAction.CONNECT, BikeLinkTileStatus.FAILED),
            model(ConnectionState.Failed(target, BleFailure.NotResponding)),
        )
    }

    @Test
    fun `adapter and permission failures open the app instead of retrying`() {
        assertEquals(
            BikeLinkTileStatus.BLUETOOTH_OFF,
            model(ConnectionState.Failed(target, BleFailure.AdapterOff)).status,
        )
        assertEquals(
            BikeLinkTileAction.OPEN_APP,
            model(ConnectionState.Failed(target, BleFailure.AdapterOff)).action,
        )
        assertEquals(
            BikeLinkTileStatus.MISSING_PERMISSIONS,
            model(ConnectionState.Failed(target, BleFailure.PermissionDenied(setOf(BlePermission.CONNECT)))).status,
        )
        assertEquals(
            BikeLinkTileStatus.BLUETOOTH_OFF,
            model(ConnectionState.BluetoothUnavailable).status,
        )
        assertEquals(
            BikeLinkTileStatus.MISSING_PERMISSIONS,
            model(ConnectionState.MissingPermissions(setOf(BlePermission.CONNECT))).status,
        )
    }

    @Test
    fun `connecting and waiting states keep their status`() {
        assertEquals(
            BikeLinkTileStatus.CONNECTING,
            model(ConnectionState.Connecting(target, attempt = 1)).status,
        )
        assertEquals(
            BikeLinkTileStatus.RECONNECTING,
            model(ConnectionState.Reconnecting(target, attempt = 1, retryAtMillis = 0)).status,
        )
        assertEquals(
            BikeLinkTileStatus.WAITING_FOR_BIKE,
            model(ConnectionState.WaitingForBike(target)).status,
        )
    }
}
