package dev.pulsarconnect.app.notifications

import dev.pulsarconnect.app.notifications.ReminderActionHandler.Outcome
import dev.pulsarconnect.core.data.InMemoryReminderRepository
import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReminderActionHandlerTest {
    private val vin = "MD2ABCDEFGH123456"
    private val otherVin = "MD2ABCDEFGH654321"
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = 1_700_000_000_000L
    private val reminders = InMemoryReminderRepository()
    private val notifications = RecordingActions()
    private val secrets = object : SecretStore {
        private var value: String? = null
        override fun read(key: String): String? = value
        override fun write(key: String, value: String) { this.value = value }
        override fun delete(key: String) { value = null }
    }
    private val sessions = SessionRepository(secrets)
    private val handler = ReminderActionHandler(sessions, reminders, notifications, zone) { now }

    @Before fun setUp() {
        sessions.save(
            StoredSession(
                Credentials(Vin.of(vin), "private-code"),
                Session("private-token", null, Instant.EPOCH),
            ),
        )
    }

    private suspend fun reminder(
        vin: String = this.vin,
        odometer: Double? = null,
        date: Long? = null,
        repeatKm: Double? = null,
        repeatDays: Long? = null,
    ) = reminders.addReminder(
        ServiceReminder(
            vin = vin, title = "Oil service", description = null,
            dueOdometerKm = odometer, dueDateMillis = date,
            repeatEveryKm = repeatKm, repeatEveryDays = repeatDays, createdAtMillis = 0,
        ),
    )

    @Test fun `complete marks the pending reminder done and records the delivery ledger`() = runBlocking {
        val target = reminder(date = 1)
        assertEquals(Outcome.Completed, handler.complete(target.id!!))
        val stored = reminders.getReminders(vin).single()
        assertTrue(stored.completed)
        assertEquals(now, stored.completedAtMillis)
        assertEquals(listOf(target.id), notifications.completed)
        assertTrue(notifications.rearmed.isEmpty())
    }

    @Test fun `completing twice is idempotent and never sends a second delivery record`() = runBlocking {
        val target = reminder(date = 1)
        handler.complete(target.id!!)
        assertEquals(Outcome.NotFound, handler.complete(target.id!!))
        assertEquals(listOf(target.id), notifications.completed)
        assertEquals(1, reminders.getReminders(vin).size)
    }

    @Test fun `completing a recurring reminder inserts exactly one next occurrence`() = runBlocking {
        val target = reminder(odometer = 1_000.0, repeatKm = 500.0, date = 5, repeatDays = 2)
        handler.complete(target.id!!)
        val all = reminders.getReminders(vin)
        assertEquals(2, all.size)
        val next = all.single { it.id != target.id }
        assertFalse(next.completed)
        assertNull(next.completedAtMillis)
        assertEquals(1_500.0, next.dueOdometerKm!!, 0.0)
        assertEquals(5 + 2 * 86_400_000L, next.dueDateMillis)
        assertEquals(now, next.createdAtMillis)

        assertEquals(Outcome.NotFound, handler.complete(target.id!!))
        assertEquals(2, reminders.getReminders(vin).size)
        assertEquals(listOf(target.id), notifications.completed)
    }

    @Test fun `snooze shifts both thresholds by one day and five hundred kilometres`() = runBlocking {
        val target = reminder(odometer = 1_000.0, date = 0)
        assertEquals(Outcome.Snoozed, handler.snooze(target.id!!))
        val stored = reminders.getReminders(vin).single()
        assertFalse(stored.completed)
        assertEquals(1_500.0, stored.dueOdometerKm!!, 0.0)
        assertEquals(86_400_000L, stored.dueDateMillis)
        assertEquals(listOf(target.id), notifications.rearmed)
        assertTrue(notifications.completed.isEmpty())
    }

    @Test fun `snooze leaves an unset threshold unset`() = runBlocking {
        val target = reminder(odometer = 250.0)
        handler.snooze(target.id!!)
        val stored = reminders.getReminders(vin).single()
        assertEquals(750.0, stored.dueOdometerKm!!, 0.0)
        assertNull(stored.dueDateMillis)
    }

    @Test fun `a reminder owned by another bike is never touched`() = runBlocking {
        val foreign = reminder(vin = otherVin, date = 1)
        assertEquals(Outcome.NotFound, handler.complete(foreign.id!!))
        assertFalse(reminders.getReminders(otherVin).single().completed)
        assertTrue(notifications.completed.isEmpty())
    }

    @Test fun `a completed reminder cannot be snoozed from a stale notification`() = runBlocking {
        val target = reminder(odometer = 10.0)
        reminders.completeReminder(target.id!!, now)
        assertEquals(Outcome.NotFound, handler.snooze(target.id!!))
        assertEquals(10.0, reminders.getReminders(vin).single().dueOdometerKm!!, 0.0)
        assertTrue(notifications.rearmed.isEmpty())
    }

    @Test fun `no signed in session means no mutation`() = runBlocking {
        val target = reminder(date = 1)
        sessions.clear()
        assertEquals(Outcome.NoSession, handler.complete(target.id!!))
        assertEquals(Outcome.NoSession, handler.snooze(target.id!!))
        assertFalse(reminders.getReminders(vin).single().completed)
        assertTrue(notifications.completed.isEmpty())
        assertTrue(notifications.rearmed.isEmpty())
    }

    private class RecordingActions : ReminderNotificationActions {
        val completed = mutableListOf<Long>()
        val rearmed = mutableListOf<Long>()
        override val enabled = true
        override fun setEnabled(enabled: Boolean) = Unit
        override fun refresh() = Unit
        override fun onSignedOut() = Unit
        override fun onReminderCompleted(id: Long) { completed += id }
        override fun onReminderRearmed(id: Long) { rearmed += id }
        override fun onReminderReopened(id: Long) = Unit
        override fun onReminderDeleted(id: Long) = Unit
    }
}
