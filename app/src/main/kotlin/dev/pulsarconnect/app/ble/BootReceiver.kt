package dev.pulsarconnect.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pulsarconnect.app.PulsarConnectApplication

/**
 * Resumes the bike link after reboot when the rider has opted into background mode.
 *
 * Only acts when [BikeLinkSettings.resumesAfterBoot] is true (mirroring + background + boot
 * all on with an armed VIN). Delegates to the controller's existing connect-with-response
 * logic; it does not add indefinite background retry or `connectGatt(autoConnect=true)`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PulsarConnectApplication
        val controller = app.container.bikeLinkController
        controller.ensureRunning()
    }
}
