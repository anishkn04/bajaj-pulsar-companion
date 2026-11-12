package dev.pulsarconnect.app.notifications

import dev.pulsarconnect.core.data.ReminderRepository
import dev.pulsarconnect.core.data.snoozed
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.session.SessionRepository
import java.time.ZoneId

/**
 * Applies notification actions to the local reminder store. It deliberately mirrors the
 * `AppViewModel` behavior, including inserting the next recurring occurrence through
 * [ReminderRepository.completeReminder], so an action taken from the shade cannot diverge
 * from the same action taken in the app.
 *
 * Runs outside the UI process; callers own the dispatcher and error reporting.
 */
class ReminderActionHandler(
    private val sessions: SessionRepository,
    private val reminders: ReminderRepository,
    private val notifications: ReminderNotificationActions,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun complete(id: Long): Outcome = when (val lookup = pending(id)) {
        is Lookup.Failed -> lookup.outcome
        is Lookup.Found -> {
            reminders.completeReminder(lookup.reminder.id!!, clock())
            notifications.onReminderCompleted(lookup.reminder.id!!)
            Outcome.Completed
        }
    }

    /** One tap shifts both thresholds; a threshold that is not set stays unset. */
    suspend fun snooze(id: Long): Outcome = when (val lookup = pending(id)) {
        is Lookup.Failed -> lookup.outcome
        is Lookup.Found -> {
            val snoozed = lookup.reminder.snoozed(SNOOZE_DAYS, SNOOZE_KILOMETRES, zone)
            reminders.updateReminder(snoozed)
            notifications.onReminderRearmed(lookup.reminder.id!!)
            Outcome.Snoozed
        }
    }

    private suspend fun pending(id: Long): Lookup {
        val session = sessions.load() ?: return Lookup.Failed(Outcome.NoSession)
        val vin = session.credentials.vin.value
        val reminder = reminders.getReminders(vin).firstOrNull { it.id == id && !it.completed }
        // The VIN check also covers a repository that returns rows for another bike.
        if (reminder == null || reminder.vin != vin) return Lookup.Failed(Outcome.NotFound)
        return Lookup.Found(reminder)
    }

    private sealed interface Lookup {
        data class Found(val reminder: ServiceReminder) : Lookup
        data class Failed(val outcome: Outcome) : Lookup
    }

    enum class Outcome { Completed, Snoozed, NoSession, NotFound }

    internal companion object {
        const val SNOOZE_DAYS = 1L
        const val SNOOZE_KILOMETRES = 500.0
    }
}
