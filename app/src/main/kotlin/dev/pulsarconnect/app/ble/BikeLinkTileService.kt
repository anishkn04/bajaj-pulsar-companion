package dev.pulsarconnect.app.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.pulsarconnect.app.MainActivity
import dev.pulsarconnect.app.PulsarConnectApplication
import dev.pulsarconnect.app.R
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkState
import dev.pulsarconnect.core.ble.BikeLinkTileAction
import dev.pulsarconnect.core.ble.BikeLinkTileStatus
import dev.pulsarconnect.core.ble.BikeLinkTileVisual
import dev.pulsarconnect.core.ble.bikeLinkTileModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for the bike link.
 *
 * It reads the same application-scoped [BikeLinkController] as the app, so the tile and the
 * Bike link screen cannot disagree. The tile only offers connect while mirroring is armed; when
 * the rider has disarmed mirroring it opens the app instead of starting the background service.
 */
class BikeLinkTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    private val container get() = (application as PulsarConnectApplication).container

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            container.bikeLinkController.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val controller = container.bikeLinkController
        when (modelFor(controller.state.value).action) {
            BikeLinkTileAction.CONNECT -> connect(controller)
            BikeLinkTileAction.DISCONNECT -> {
                controller.disconnect()
                render(controller.state.value)
            }
            BikeLinkTileAction.OPEN_APP -> openApp()
        }
    }

    private fun connect(controller: BikeLinkController) {
        val settings = controller.preferences.settings.value
        val hasArmedBike = settings.armedVin?.let { container.bikeDevices.selected(it) } != null
        if (!settings.mirroring || !hasArmedBike) {
            openApp()
            return
        }
        // A tile tap is a user interaction, but a missing runtime permission or a disabled
        // adapter still needs the app to explain and request it.
        container.bleTransport.refreshAdapterState()
        val adapterOff = container.bleTransport.adapterState.value == AdapterState.OFF
        if (adapterOff || container.bleTransport.missingPermissions().isNotEmpty()) {
            openApp()
            return
        }
        if (shouldRunBikeLinkService(settings, hasArmedBike)) {
            // The persistent service is already armed, so let it own the link. If the platform
            // still refuses the foreground start, the controller can connect while this process
            // remains alive.
            runCatching { container.bikeLinkRuntime.setEnabled(true) }
        }
        controller.ensureRunning()
        render(controller.state.value)
    }

    private fun modelFor(link: BikeLinkState) = bikeLinkTileModel(
        settings = link.settings,
        connection = link.connection,
        hasArmedBike = link.settings.armedVin?.let { container.bikeDevices.selected(it) } != null,
    )

    private fun render(link: BikeLinkState) {
        val tile = qsTile ?: return
        val model = modelFor(link)
        tile.state = when (model.visual) {
            BikeLinkTileVisual.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            BikeLinkTileVisual.INACTIVE -> Tile.STATE_INACTIVE
            BikeLinkTileVisual.ACTIVE -> Tile.STATE_ACTIVE
        }
        tile.label = getString(R.string.bike_link_tile_label)
        val status = getString(model.status.labelRes())
        tile.contentDescription = getString(R.string.bike_link_tile_content_description, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = status
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        unlockAndRun {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun BikeLinkTileStatus.labelRes(): Int = when (this) {
        BikeLinkTileStatus.NO_BIKE -> R.string.bike_link_tile_no_bike
        BikeLinkTileStatus.MIRRORING_OFF -> R.string.bike_link_tile_mirroring_off
        BikeLinkTileStatus.BLUETOOTH_OFF -> R.string.bike_link_tile_bluetooth_off
        BikeLinkTileStatus.MISSING_PERMISSIONS -> R.string.bike_link_tile_missing_permissions
        BikeLinkTileStatus.CONNECTED -> R.string.bike_link_tile_connected
        BikeLinkTileStatus.CONNECTING -> R.string.bike_link_tile_connecting
        BikeLinkTileStatus.RECONNECTING -> R.string.bike_link_tile_reconnecting
        BikeLinkTileStatus.WAITING_FOR_BIKE -> R.string.bike_link_tile_waiting
        BikeLinkTileStatus.DISCONNECTED -> R.string.bike_link_tile_disconnected
        BikeLinkTileStatus.FAILED -> R.string.bike_link_tile_failed
    }
}
