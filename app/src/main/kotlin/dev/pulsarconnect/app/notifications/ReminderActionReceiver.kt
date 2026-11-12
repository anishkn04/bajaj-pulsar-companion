package dev.pulsarconnect.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pulsarconnect.app.PulsarConnectApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles "Mark done" and "Snooze" from a reminder notification while the UI may not be
 * running. Intents are explicit and the receiver is not exported, so only our own
 * [android.app.PendingIntent]s can mutate reminders.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.component?.packageName != context.packageName) return
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, NO_ID)
        if (id <= 0) return
        val action = intent.action ?: return
        if (action != ACTION_MARK_DONE && action != ACTION_SNOOZE) return
        val container = (context.applicationContext as? PulsarConnectApplication)?.container ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val outcome = when (action) {
                    ACTION_MARK_DONE -> container.reminderActionHandler.complete(id)
                    else -> container.reminderActionHandler.snooze(id)
                }
                if (outcome == ReminderActionHandler.Outcome.Completed ||
                    outcome == ReminderActionHandler.Outcome.Snoozed
                ) {
                    container.reminderActions.notifyChanged()
                }
            } catch (_: Exception) {
                // Keep the notification so the rider can retry or open the app instead.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "dev.pulsarconnect.app.notifications.action.MARK_DONE"
        const val ACTION_SNOOZE = "dev.pulsarconnect.app.notifications.action.SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        private const val NO_ID = -1L

        internal fun intent(context: Context, action: String, reminderId: Long): Intent =
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_REMINDER_ID, reminderId)

        /** Stable, distinct request codes so the two actions never replace each other. */
        internal fun requestCode(action: String, reminderId: Long): Int =
            ((reminderId * 2).toInt() + if (action == ACTION_MARK_DONE) 0 else 1)
    }
}
