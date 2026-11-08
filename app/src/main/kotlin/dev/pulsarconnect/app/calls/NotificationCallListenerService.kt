package dev.pulsarconnect.app.calls

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.pulsarconnect.app.PulsarConnectApplication

/**
 * Reads dialer call notifications only while the rider has opted in.
 *
 * The service is bound by the system once notification access is granted, but it does nothing
 * unless mirroring is on and at least one call feature is enabled. It never stores, logs or
 * exports notification content; only a privacy-safe display label or the missed-call marker is
 * published to [CallNotificationBus].
 */
class NotificationCallListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val container = (application as PulsarConnectApplication).container
        val settings = container.bikeLinkPreferences.settings.value
        if (!settings.mirroring || !settings.mirrorsAnyCall) return

        // Check the package before touching any extras so other apps' notifications are never read.
        if (!container.callNotificationParser.accepts(sbn.packageName)) return

        val snapshot = CallNotificationSnapshot(
            packageName = sbn.packageName,
            category = notification.category,
            title = notification.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString(),
            text = notification.extras
                ?.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString(),
        )

        when (val signal = container.callNotificationParser.parse(snapshot)) {
            is CallNotificationSignal.CallerName ->
                if (settings.mirrorCallerName) container.callNotificationBus.publish(signal)
            CallNotificationSignal.Missed ->
                if (settings.mirrorMissedCalls) container.callNotificationBus.publish(signal)
            null -> Unit
        }
    }
}
