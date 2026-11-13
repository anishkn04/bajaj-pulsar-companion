package dev.pulsarconnect.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.pulsarconnect.app.MainActivity
import dev.pulsarconnect.app.R
import dev.pulsarconnect.core.data.OdometerRepository
import dev.pulsarconnect.core.data.ReminderRepository
import dev.pulsarconnect.core.data.TripRepository
import dev.pulsarconnect.core.data.latestOdometerKm
import dev.pulsarconnect.core.session.SessionRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReminderNotificationManager(
    context: Context,
    private val sessionRepository: SessionRepository,
    private val trips: TripRepository,
    private val reminders: ReminderRepository,
    private val odometers: OdometerRepository,
) : ReminderNotificationActions {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val work get() = WorkManager.getInstance(context)

    override val enabled: Boolean get() = preferences.getBoolean(ENABLED, false)

    fun ensureChannel() {
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Service reminders", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminders to review upcoming or overdue service"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        })
    }

    /** Reconcile startup state without treating persisted credentials as a restored session. */
    fun initialize() {
        synchronized(deliveryGate) {
            ensureChannel()
            if (allowed()) schedule() else cancelWorkAndNotifications()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        synchronized(deliveryGate) {
            preferences.edit().putBoolean(ENABLED, enabled).apply()
            generation++
            if (enabled && !preferences.getBoolean(PAUSED, false)) {
                schedule()
            } else {
                cancelWorkAndNotifications()
            }
        }
    }

    /** Call only for a confirmed signed-in session: after sign-in/restore or local data changes. */
    override fun refresh() {
        synchronized(deliveryGate) {
            preferences.edit().putBoolean(PAUSED, false).apply()
            if (enabled) schedule() else cancelWorkAndNotifications()
        }
    }

    override fun onSignedOut() {
        synchronized(deliveryGate) {
            generation++
            // Persist the pause too: an old worker after process recreation must not use
            // credentials whose asynchronous sign-out cleanup has not finished yet.
            preferences.edit().putBoolean(PAUSED, true).apply()
            cancelWorkAndNotifications()
        }
    }

    override fun onReminderCompleted(id: Long) {
        synchronized(deliveryGate) {
            remember(COMPLETED, id)
            notifications.cancel(tag(id), NOTIFICATION_ID)
        }
    }

    override fun onReminderRearmed(id: Long) {
        synchronized(deliveryGate) {
            forget(DELIVERED, id)
            notifications.cancel(tag(id), NOTIFICATION_ID)
        }
    }

    override fun onReminderReopened(id: Long) {
        synchronized(deliveryGate) {
            forget(COMPLETED, id)
            forget(DELIVERED, id)
            notifications.cancel(tag(id), NOTIFICATION_ID)
        }
    }

    override fun onReminderDeleted(id: Long) {
        synchronized(deliveryGate) {
            forget(COMPLETED, id)
            forget(DELIVERED, id)
            notifications.cancel(tag(id), NOTIFICATION_ID)
        }
    }

    private fun schedule() {
        ensureChannel()
        work.enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderCheckWorker>(12, TimeUnit.HOURS).build(),
        )
        // Preserve refreshes arriving after an active check has read its due snapshot.
        // A failed/cancelled chain must not prevent later checks from running.
        work.enqueueUniqueWork(
            IMMEDIATE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReminderCheckWorker>().build(),
        )
    }

    private fun cancelWorkAndNotifications() {
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelUniqueWork(IMMEDIATE_WORK)
        // Never cancel unrelated app notifications, and don't rely on the delivery ledger
        // to find notifications posted immediately before an interrupted ledger write.
        notifications.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { notifications.cancel(it.tag, it.id) }
    }

    private fun allowed() = enabled && !preferences.getBoolean(PAUSED, false)
    private fun remembered(key: String, id: Long) = preferences.getStringSet(key, emptySet())!!.contains(id.toString())
    private fun remember(key: String, id: Long) {
        val ids = preferences.getStringSet(key, emptySet())!!.toMutableSet()
        ids.add(id.toString())
        preferences.edit().putStringSet(key, ids).apply()
    }

    private fun forget(key: String, id: Long) {
        val ids = preferences.getStringSet(key, emptySet())!!.toMutableSet()
        if (ids.remove(id.toString())) preferences.edit().putStringSet(key, ids).apply()
    }

    private fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL_ID)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    internal suspend fun checkDueReminders() = withContext(Dispatchers.IO) {
        val startedGeneration = synchronized(deliveryGate) {
            if (!allowed()) return@withContext
            generation
        }
        val session = sessionRepository.load() ?: return@withContext
        val vin = session.credentials.vin.value
        val odometer = latestOdometerKm(vin, trips, odometers) ?: Double.NaN
        val due = reminders.getDueReminders(vin, odometer, System.currentTimeMillis())
        for (reminder in due) {
            val id = reminder.id ?: continue
            if (reminder.vin != vin || reminder.completed) continue
            // Session/Keystore IO stays outside the gate, so synchronous UI hooks never
            // wait for storage. The generation check closes the sign-out race afterward.
            // Token rotation does not change ownership of these local reminders.
            if (sessionRepository.load()?.credentials?.vin?.value != vin) return@withContext
            synchronized(deliveryGate) {
                if (generation != startedGeneration || !allowed()) return@withContext
                if (remembered(DELIVERED, id) || remembered(COMPLETED, id)) return@synchronized
                try {
                    if (!canPost()) return@synchronized
                    notifications.notify(tag(id), NOTIFICATION_ID, notification(id))
                    // A blocked channel/permission must not consume the delivery record.
                    if (canPost()) remember(DELIVERED, id)
                } catch (_: SecurityException) {
                    // Permission can be revoked between checking it and notify(). A later
                    // refresh/periodic check can try again, without a poisoned ledger.
                }
            }
        }
    }

    private fun notification(reminderId: Long): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun builder() = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_reminder)
            .setContentTitle("Service reminder")
            .setContentText("Open Pulsar Connect to review your service reminders.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        // The lockscreen-facing public version stays action-free: acting on a reminder must
        // not be possible without unlocking the phone.
        return builder()
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(action("Mark done", ReminderActionReceiver.ACTION_MARK_DONE, reminderId))
            .addAction(action("Snooze", ReminderActionReceiver.ACTION_SNOOZE, reminderId))
            .setPublicVersion(builder().setVisibility(Notification.VISIBILITY_PUBLIC).build())
            .build()
    }

    private fun action(title: String, action: String, reminderId: Long): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_service_reminder),
            title,
            PendingIntent.getBroadcast(
                context,
                ReminderActionReceiver.requestCode(action, reminderId),
                ReminderActionReceiver.intent(context, action, reminderId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    internal companion object {
        const val CHANNEL_ID = "service_reminders"
        const val PERIODIC_WORK = "service_reminders_periodic"
        const val IMMEDIATE_WORK = "service_reminders_immediate"
        private const val ENABLED = "enabled"
        private const val PAUSED = "paused"
        private const val DELIVERED = "delivered_ids"
        private const val COMPLETED = "completed_ids"
        private const val TAG_PREFIX = "service_reminder:"
        private const val NOTIFICATION_ID = 1
        internal fun tag(id: Long) = "$TAG_PREFIX$id"

        // WorkManager and the app controller share this process-wide gate, including
        // when a test/composition root creates a second controller instance.
        private val deliveryGate = Any()
        private var generation = 0L
    }
}
