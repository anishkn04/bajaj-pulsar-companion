package dev.pulsarconnect.app.notifications

import android.app.Notification
import android.app.NotificationManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@Implements(NotificationManager::class)
class RevocableNotificationManager : ShadowNotificationManager() {
    var revokeNextPost = false

    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification) {
        if (revokeNextPost) {
            revokeNextPost = false
            throw SecurityException("Permission revoked immediately before posting")
        }
        super.notify(tag, id, notification)
    }
}
