package dev.pulsarconnect.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.pulsarconnect.app.MainActivity
import dev.pulsarconnect.app.R
import dev.pulsarconnect.core.ble.BikeDeviceStore
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeLinkSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** The service only exists while the rider has an armed background link to a chosen bike. */
internal fun shouldRunBikeLinkService(
    settings: BikeLinkSettings,
    hasSelectedBike: Boolean,
): Boolean = settings.runsInBackground && settings.armedVin != null && hasSelectedBike

/** Maps an incoming intent to the service's lifecycle commands. */
internal enum class BikeLinkServiceCommand { RESUME, PAUSE, STOP }

internal fun bikeLinkServiceCommand(action: String?): BikeLinkServiceCommand = when (action) {
    // A sticky restart arrives with a null intent and must resume foreground operation.
    BikeLinkService.ACTION_STOP -> BikeLinkServiceCommand.STOP
    BikeLinkService.ACTION_PAUSE -> BikeLinkServiceCommand.PAUSE
    else -> BikeLinkServiceCommand.RESUME
}

/**
 * Foreground service that keeps the bike link alive while mirroring is on.
 *
 * Lifecycle is driven by [BikeLinkRuntime]: [ACTION_START] binds the service to the active
 * controller and [ACTION_STOP] releases it. The service is sticky so the system restarts it
 * after a kill, but it only resumes mirroring when [BikeLinkPreferences.mirroring] is still on.
 *
 * Runs as `connectedDevice` because the phone is acting as a BLE client to the bike.
 */
class BikeLinkService : Service() {

    companion object {
        /** Start the service and attach it to the controller. */
        const val ACTION_START = "dev.pulsarconnect.app.ble.action.START"

        /** Stop the service and detach it from the controller. */
        const val ACTION_STOP = "dev.pulsarconnect.app.ble.action.STOP"

        private const val CHANNEL_ID = "dev.pulsarconnect.app.ble.bike_link"
        private const val NOTIFICATION_ID = 1

        /** Disarms mirroring without forgetting the armed bike or background preference. */
        const val ACTION_PAUSE = "dev.pulsarconnect.app.ble.action.PAUSE"
    }

    private lateinit var controller: BikeLinkController
    private lateinit var preferences: BikeLinkPreferences
    private lateinit var bikeDevices: BikeDeviceStore
    private var attached = false
    private var observerScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as dev.pulsarconnect.app.PulsarConnectApplication
        controller = app.container.bikeLinkController
        preferences = app.container.bikeLinkPreferences
        bikeDevices = app.container.bikeDevices

        when (bikeLinkServiceCommand(intent?.action)) {
            BikeLinkServiceCommand.STOP -> {
                // The notification action is a rider request to stop mirroring, not just to
                // dismiss the notification. Runtime-driven stops use stopService() instead.
                controller.setMirroring(false)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                detach()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            BikeLinkServiceCommand.PAUSE -> {
                // Pause persists the rider's intent to stop mirroring. The armed bike and the
                // background preference stay, so the next app launch can resume the same link.
                controller.setMirroring(false)
            }
            BikeLinkServiceCommand.RESUME -> Unit
        }

        val settings = preferences.settings.value
        if (!shouldRunBikeLinkService(settings, hasSelectedBike(settings))) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            detach()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Start foreground before touching the controller so a slow connect cannot trip the
        // system's foreground-service timeout.
        startForeground(Companion.NOTIFICATION_ID, buildNotification())
        attach()
        // (Re)attach to the armed bike after boot or a process restart. The controller's
        // existing connect-with-response logic handles the actual connection; this service
        // only keeps the process alive while mirroring is on.
        controller.ensureRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun attach() {
        if (attached) return
        attached = true
        // Mirroring may have been disabled while the process was dead; honour the persisted
        // intent and stop as soon as the rider disarms the background link.
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            preferences.settings.onEach { settings ->
                if (!shouldRunBikeLinkService(settings, hasSelectedBike(settings))) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }.launchIn(scope)
            // Keep the ongoing notification truthful as the link moves between connecting,
            // waiting, connected, and failed states.
            controller.state.onEach {
                if (attached) updateNotification()
            }.launchIn(scope)
        }
    }

    private fun hasSelectedBike(settings: BikeLinkSettings): Boolean =
        settings.armedVin?.let { bikeDevices.selected(it) } != null

    private fun detach() {
        attached = false
        observerScope?.cancel()
        observerScope = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            Companion.CHANNEL_ID,
            getString(R.string.bike_link_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bike_link_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this)
                .notify(Companion.NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Notification permission can be revoked while the service is running. The
            // foreground notification is still owned by the system; only our update is lost.
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(applicationContext, BikeLinkService::class.java).apply {
            action = Companion.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            applicationContext, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseIntent = Intent(applicationContext, BikeLinkService::class.java).apply {
            action = Companion.ACTION_PAUSE
        }
        val pausePending = PendingIntent.getService(
            applicationContext, 1, pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_MAIN
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = controller.state.value
        val armedVin = preferences.settings.value.armedVin
        val bikeName = bikeLinkDisplayName(
            state.selected?.name ?: armedVin?.let { bikeDevices.selected(it)?.name },
        )
        return NotificationCompat.Builder(applicationContext, Companion.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_reminder)
            .setContentTitle(getString(R.string.bike_link_notification_title_named, bikeName))
            .setContentText(bikeLinkNotificationState(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_service_reminder, getString(R.string.bike_link_pause_action), pausePending)
            .addAction(R.drawable.ic_service_reminder, getString(R.string.bike_link_stop_action), stopPending)
            .setContentIntent(openPending)
            .build()
    }
}
