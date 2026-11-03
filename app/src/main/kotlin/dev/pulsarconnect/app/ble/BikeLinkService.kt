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
import dev.pulsarconnect.app.MainActivity
import dev.pulsarconnect.app.R
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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
    }

    private lateinit var controller: BikeLinkController
    private lateinit var preferences: BikeLinkPreferences
    private var attached = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when {
            action == Companion.ACTION_START -> {
                val app = applicationContext as dev.pulsarconnect.app.PulsarConnectApplication
                controller = app.container.bikeLinkController
                preferences = app.container.bikeLinkPreferences
                attach()
                // (Re)attach to the armed bike after boot or a process restart. The controller's
                // existing connect-with-response logic handles the actual connection; this service
                // only keeps the process alive while mirroring is on.
                controller.ensureRunning()
                startForeground(Companion.NOTIFICATION_ID, buildNotification())
            }
            action == Companion.ACTION_STOP -> {
                stopForeground(true)
                detach()
                stopSelf(startId)
            }
        }
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
        // Mirroring may have been disabled while the process was dead; honour the persisted intent.
        if (!preferences.settings.value.mirroring) {
            stopSelf()
            return
        }
        preferences.settings.onEach { settings ->
            if (!settings.mirroring) stopSelf()
        }.launchIn(scope)
    }

    private fun detach() {
        if (!attached) return
        attached = false
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

    private fun buildNotification(): Notification {
        val stopIntent = Intent(applicationContext, BikeLinkService::class.java).apply {
            action = Companion.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            applicationContext, 0, stopIntent,
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
        return NotificationCompat.Builder(applicationContext, Companion.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_reminder)
            .setContentTitle(getString(R.string.bike_link_notification_title))
            .setContentText(getString(R.string.bike_link_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_service_reminder, getString(R.string.bike_link_stop_action), stopPending)
            .setContentIntent(openPending)
            .build()
    }
}
