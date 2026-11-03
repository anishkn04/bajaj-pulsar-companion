package dev.pulsarconnect.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.pulsarconnect.app.PulsarConnectApplication

/**
 * Resumes the bike link after reboot when the rider has opted into background mode.
 *
 * Only acts when [BikeLinkSettings.resumesAfterBoot] is true (mirroring + background + boot
 * all on with an armed VIN). Starts [BikeLinkService], whose [ACTION_START] handler drives
 * the controller's existing connect-with-response logic; it does not add indefinite background
 * retry or `connectGatt(autoConnect=true)`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PulsarConnectApplication
        val settings = app.container.bikeLinkPreferences.settings.value
        if (!settings.resumesAfterBoot) return

        val start = Intent(context.applicationContext, BikeLinkService::class.java).apply {
            action = BikeLinkService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.applicationContext.startForegroundService(start)
        } else {
            context.applicationContext.startService(start)
        }
    }
}
