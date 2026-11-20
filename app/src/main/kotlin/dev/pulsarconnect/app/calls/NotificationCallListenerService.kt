package dev.pulsarconnect.app.calls

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.pulsarconnect.app.PulsarConnectApplication
import dev.pulsarconnect.app.nav.NavNotificationSnapshot
import dev.pulsarconnect.core.ble.NavEvent

/**
 * Reads dialer call notifications and (when opted in) Google Maps / Organic Maps navigation
 * notifications while the rider has enabled mirroring.
 *
 * The service is bound by the system once notification access is granted, but it does nothing
 * unless mirroring is on and at least one notification-backed feature is enabled. It never
 * stores, logs or exports notification content.
 */
class NotificationCallListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val container = (application as PulsarConnectApplication).container
        val settings = container.bikeLinkPreferences.settings.value
        if (!settings.mirroring || !settings.mirrorsAnyNotification) return

        // Check the package before touching any extras so unrelated apps' notifications are never read.
        if (settings.mirrorsAnyCall && container.callNotificationParser.accepts(sbn.packageName)) {
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
            return
        }

        if (settings.mirrorNavigation && container.navNotificationParser.accepts(sbn.packageName)) {
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val snapshot = NavNotificationSnapshot(
                packageName = sbn.packageName,
                category = notification.category,
                isOngoing = isOngoing,
                title = notification.extras
                    ?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString(),
                text = notification.extras
                    ?.getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString(),
                subText = notification.extras
                    ?.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    ?.toString(),
            )
            val cue = container.navNotificationParser.parse(snapshot)
            if (cue != null) {
                container.navNotificationBus.publish(NavEvent.Update(cue))
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        val container = (application as PulsarConnectApplication).container
        val settings = container.bikeLinkPreferences.settings.value
        if (!settings.mirrorNavigation) return
        if (!container.navNotificationParser.accepts(packageName)) return
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing || notification.category == dev.pulsarconnect.app.nav.NavNotificationParser.CATEGORY_NAVIGATION) {
            container.navNotificationBus.publish(NavEvent.Ended)
        }
    }
}
