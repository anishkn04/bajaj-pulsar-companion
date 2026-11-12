package dev.pulsarconnect.app.ui

import dev.pulsarconnect.core.model.ServiceReminder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStatsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()

    private fun reminder(
        id: Long,
        date: Long? = null,
        odometer: Double? = null,
        completed: Boolean = false,
    ) = ServiceReminder(
        id = id, vin = "MD2ABCDEFGH123456", title = "Reminder $id", description = null,
        dueOdometerKm = odometer, dueDateMillis = date, completed = completed, createdAtMillis = 0,
    )

    @Test
    fun `month range follows the local calendar month`() {
        val (start, end) = monthRange(Instant.parse("2026-10-31T20:00:00Z").toEpochMilli(), zone)
        // 20:00 UTC on 31 October is 01:30 on 1 November in Asia/Kolkata.
        assertEquals(Instant.parse("2026-10-31T18:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-30T18:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `due reminders take priority over upcoming ones`() {
        val upcoming = reminder(1, date = now + 86_400_000)
        val due = reminder(2, date = now - 1)
        assertEquals(2L, nextPendingReminder(listOf(upcoming, due), 100.0, now)?.id)
        assertEquals(1L, nextPendingReminder(listOf(upcoming), 100.0, now)?.id)
        assertEquals(2L, nextPendingReminder(listOf(upcoming, due.copy(dueDateMillis = null, dueOdometerKm = 50.0)), 100.0, now)?.id)
    }

    @Test
    fun `completed reminders are ignored`() {
        val completed = reminder(1, date = now - 1, completed = true)
        assertNull(nextPendingReminder(listOf(completed), 100.0, now))
        assertNull(nextPendingReminder(emptyList(), null, now))
    }

    @Test
    fun `odometer-only reminders are due once the recorded reading passes them`() {
        val future = reminder(1, odometer = 500.0)
        assertEquals(1L, nextPendingReminder(listOf(future), 100.0, now)?.id)
        assertEquals(1L, nextPendingReminder(listOf(future), 500.0, now)?.id)
        // Without a recorded reading it cannot be due yet, but it is still the next reminder.
        assertEquals(1L, nextPendingReminder(listOf(future), null, now)?.id)
    }

    @Test
    fun `timing label explains date and odometer thresholds`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val both = reminderTimingLabel(reminder(1, date = now - 1, odometer = 1_500.0), now)
            assertTrue(both, both.contains("date passed"))
            assertTrue(both, both.contains("1,500 km"))

            val upcoming = reminderTimingLabel(reminder(1, date = now + 86_400_000), now)
            assertTrue(upcoming, upcoming.startsWith("on "))

            val odometerOnly = reminderTimingLabel(reminder(1, odometer = 42.5), now)
            assertTrue(odometerOnly, odometerOnly.contains("42.5 km"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
