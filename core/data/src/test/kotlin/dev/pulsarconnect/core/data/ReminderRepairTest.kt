package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.ServiceReminder
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ReminderRepairTest {

    private val reminders = InMemoryReminderRepository()

    private suspend fun reminder(
        title: String = "Oil change",
        odometer: Double? = 1_000.0,
        date: Long? = null,
        completed: Boolean = false,
    ) = reminders.addReminder(ServiceReminder(
        vin = "DEV-BIKE-A", title = title, description = null,
        dueOdometerKm = odometer, dueDateMillis = date,
        completed = completed, completedAtMillis = if (completed) 5_000 else null,
        createdAtMillis = 0,
    ))

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected repository contract failure.
        }
    }

    @Test
    fun `editing replaces rider fields and keeps completion state`() = runTest {
        val original = reminder(title = "Oil", odometer = 1_000.0, completed = true)

        val updated = reminders.updateReminder(
            original.copy(
                title = " Chain ",
                description = "Clean and lube",
                dueOdometerKm = 1_500.0,
                dueDateMillis = 20_000,
            ),
        )

        assertEquals(" Chain ", updated.title)
        assertEquals("Clean and lube", updated.description)
        assertEquals(1_500.0, updated.dueOdometerKm)
        assertEquals(20_000L, updated.dueDateMillis)
        assertTrue(updated.completed)
        assertEquals(5_000L, updated.completedAtMillis)
        assertEquals(updated, reminders.getReminders("DEV-BIKE-A").single())
    }

    @Test
    fun `editing rejects invalid content and missing reminders`() = runTest {
        val original = reminder()
        for (invalid in listOf(
            original.copy(title = " "),
            original.copy(dueOdometerKm = null, dueDateMillis = null),
            original.copy(dueOdometerKm = -1.0),
            original.copy(dueOdometerKm = Double.NaN),
            original.copy(dueOdometerKm = Double.POSITIVE_INFINITY),
        )) {
            rejects { reminders.updateReminder(invalid) }
        }
        rejects { reminders.updateReminder(original.copy(id = 999)) }
        rejects { reminders.updateReminder(original.copy(id = null)) }
        assertEquals(original, reminders.getReminders("DEV-BIKE-A").single())
    }

    @Test
    fun `deleting removes only the selected reminder`() = runTest {
        val first = reminder(title = "First")
        val second = reminder(title = "Second")

        reminders.deleteReminder(first.id!!)

        assertEquals(listOf(second), reminders.getReminders("DEV-BIKE-A"))
        rejects { reminders.deleteReminder(first.id!!) }
    }

    @Test
    fun `reopening clears completion so the reminder is due again`() = runTest {
        val completed = reminder(odometer = 100.0, completed = true)
        assertFalse(completed.isDue(200.0, 10_000))

        val reopened = reminders.reopenReminder(completed.id!!)

        assertFalse(reopened.completed)
        assertNull(reopened.completedAtMillis)
        assertTrue(reopened.isDue(200.0, 10_000))
        assertEquals(reopened, reminders.getReminders("DEV-BIKE-A").single())
        rejects { reminders.reopenReminder(999) }
    }

    @Test
    fun `completing a recurring reminder creates exactly one next occurrence`() = runTest {
        val date = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        val original = reminders.addReminder(
            ServiceReminder(
                vin = "DEV-BIKE-A", title = "Service", description = null,
                dueOdometerKm = 1_000.0, dueDateMillis = date,
                repeatEveryKm = 500.0, repeatEveryDays = 30, createdAtMillis = 0,
            ),
        )

        reminders.completeReminder(original.id!!, 10_000)

        val all = reminders.getReminders("DEV-BIKE-A")
        assertEquals(2, all.size)
        val completed = all.single { it.id == original.id }
        assertTrue(completed.completed)
        assertEquals(10_000L, completed.completedAtMillis)
        val next = all.single { it.id != original.id }
        assertEquals(1_500.0, next.dueOdometerKm)
        assertEquals(date + 30L * 86_400_000, next.dueDateMillis)
        assertEquals(500.0, next.repeatEveryKm)
        assertEquals(30L, next.repeatEveryDays)
        assertFalse(next.completed)
        assertNull(next.completedAtMillis)

        // Retrying completion must not add another occurrence.
        reminders.completeReminder(original.id!!, 11_000)
        assertEquals(2, reminders.getReminders("DEV-BIKE-A").size)

        // Completing the next occurrence advances once, rather than looping.
        reminders.completeReminder(next.id!!, 12_000)
        val advanced = reminders.getReminders("DEV-BIKE-A").single { !it.completed }
        assertEquals(2_000.0, advanced.dueOdometerKm)
        assertEquals(date + 60L * 86_400_000, advanced.dueDateMillis)
    }

    @Test
    fun `a one-off reminder does not create a next occurrence`() = runTest {
        val original = reminder(odometer = 1_000.0)
        reminders.completeReminder(original.id!!, 10_000)
        assertEquals(listOf(original.copy(completed = true, completedAtMillis = 10_000)), reminders.getReminders("DEV-BIKE-A"))
    }

    @Test
    fun `recurring reminders require a matching due threshold and positive intervals`() = runTest {
        val original = reminder()
        for (invalid in listOf(
            original.copy(dueOdometerKm = null, repeatEveryKm = 500.0),
            original.copy(repeatEveryKm = 0.0),
            original.copy(repeatEveryKm = Double.NaN),
            original.copy(repeatEveryDays = 0),
            original.copy(repeatEveryDays = 30),
        )) {
            rejects { reminders.updateReminder(invalid) }
        }
        assertEquals(original, reminders.getReminders("DEV-BIKE-A").single())
    }

    @Test
    fun `snoozing shifts only the thresholds that exist and keeps completion state`() {
        val zone = ZoneOffset.UTC
        val date = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        val both = ServiceReminder(
            vin = "DEV-BIKE-A", title = "Service", description = null,
            dueOdometerKm = 1_000.0, dueDateMillis = date, createdAtMillis = 0,
        )

        val byDay = both.snoozed(days = 1, kilometres = 0.0, zone = zone)
        assertEquals(date + 86_400_000, byDay.dueDateMillis)
        assertEquals(1_000.0, byDay.dueOdometerKm)
        assertFalse(byDay.completed)

        val byDistance = both.snoozed(days = 0, kilometres = 500.0, zone = zone)
        assertEquals(date, byDistance.dueDateMillis)
        assertEquals(1_500.0, byDistance.dueOdometerKm)

        val dateOnly = both.copy(dueOdometerKm = null).snoozed(days = 1, kilometres = 500.0, zone = zone)
        assertNull(dateOnly.dueOdometerKm)
        assertEquals(date + 86_400_000, dateOnly.dueDateMillis)

        val completed = both.copy(completed = true, completedAtMillis = 42).snoozed(days = 1, kilometres = 0.0, zone = zone)
        assertTrue(completed.completed)
        assertEquals(42L, completed.completedAtMillis)
    }
}
