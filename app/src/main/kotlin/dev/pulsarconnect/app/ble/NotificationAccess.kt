package dev.pulsarconnect.app.ble

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Notification access is a special user-granted permission, not a runtime permission.
 *
 * The app never reads notification content unless the rider enables a call feature, and this
 * helper only reports whether the system currently has the listener enabled.
 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
            .contains(context.applicationContext.packageName)

    /** Opens the system screen where the rider can grant or revoke access. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        // Some OEMs expose a per-app detail screen, but the list screen is universal.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
