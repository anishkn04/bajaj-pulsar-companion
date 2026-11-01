package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.ServiceReminder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TripReminderRegressionTest {
    private var now = 10_000L
    private val trips: TripRepository = InMemoryTripRepository(clock = { now })
    private val reminders: ReminderRepository = InMemoryReminderRepository()

    @Test
    fun `active trip lookup ignores history pagination after clock rollback and isolates vins`() = runTest {
        assertNull(trips.getActiveTrip("VIN"))
        now = 20_000L
        repeat(30) {
            val trip = trips.startTrip("VIN", 100.0, null, null)
            now++
            trips.endTrip(trip, 101.0, null, null)
            now++
        }
        assertNull(trips.getActiveTrip("VIN"))

        now = 1_000L
        val active = trips.startTrip("VIN", 101.0, null, null)
        now = 2_000L
        val other = trips.startTrip("OTHER", 0.0, null, null)
        val firstPage = trips.getTrips("VIN", 25, 0)
        assertEquals(25, firstPage.size)
        assertTrue(firstPage.all { it.endTime != null && it.startTime > active.startTime })
        assertFalse(firstPage.any { it.id == active.id })
        assertEquals(active, trips.getActiveTrip("VIN"))
        assertEquals(other, trips.getActiveTrip("OTHER"))
        assertNull(trips.getActiveTrip("MISSING"))

        trips.endTrip(active, 102.0, null, null)
        assertNull(trips.getActiveTrip("VIN"))
        assertEquals(other, trips.getActiveTrip("OTHER"))
        trips.endTrip(other, 0.0, null, null)
        assertNull(trips.getActiveTrip("OTHER"))
    }

    @Test
    fun `clock controls trip times distance and average speed`() = runTest {
        val trip = trips.startTrip("VIN", 100.0, null, null)
        assertEquals(10_000L, trip.startTime)
        now += 3_600_000
        val ended = trips.endTrip(trip, 125.0, 0.0, 0.0)
        assertEquals(now, ended.endTime)
        assertEquals(25.0, ended.distanceKm)
        assertEquals(25.0, ended.avgSpeedKmph)
        assertEquals(3_600L, ended.durationSeconds)
        assertEquals(ended, trips.getTrip(trip.id!!))
    }

    @Test
    fun `same time trips sort by id descending including pagination and vin isolation`() = runTest {
        val first = trips.startTrip("VIN", 0.0, null, null)
        val second = trips.startTrip("VIN", 0.0, null, null)
        val third = trips.startTrip("VIN", 0.0, null, null)
        trips.startTrip("OTHER", 0.0, null, null)
        assertEquals(listOf(third, second, first), trips.getTrips("VIN", 10, 0))
        assertEquals(listOf(second), trips.getTrips("VIN", 1, 1))
        now++
        val latest = trips.startTrip("VIN", 0.0, null, null)
        assertEquals(latest, trips.getTrips("VIN", 1, 0).single())
    }

    @Test
    fun `start rejects negative and nonfinite odometers without creating trips`() = runTest {
        for (odometer in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { trips.startTrip("VIN", odometer, null, null) }
            }
        }
        assertTrue(trips.getTrips("VIN", 10, 0).isEmpty())
        assertEquals(0.0, trips.startTrip("VIN", 0.0, null, null).startOdometer)
    }

    @Test
    fun `end rejects invalid or decreasing odometers and preserves active trip`() = runTest {
        val trip = trips.startTrip("VIN", 100.0, null, null)
        for (odometer in listOf(-1.0, 99.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { trips.endTrip(trip, odometer, null, null) }
            }
            assertEquals(trip, trips.getTrip(trip.id!!))
        }
        assertEquals(0.0, trips.endTrip(trip, 100.0, null, null).distanceKm)
    }

    @Test
    fun `end requires a persisted active trip and rejects repeat ending through stale input`() = runTest {
        val trip = trips.startTrip("VIN", 0.0, null, null)
        for (missing in listOf(trip.copy(id = null), trip.copy(id = Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { trips.endTrip(missing, 1.0, null, null) }
            }
        }
        now++
        val ended = trips.endTrip(trip, 1.0, null, null)
        for (stale in listOf(trip, ended)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { trips.endTrip(stale, 2.0, null, null) }
            }
        }
        assertEquals(ended, trips.getTrip(trip.id!!))
    }

    @Test
    fun `end uses stored start values rather than caller supplied copies`() = runTest {
        val trip = trips.startTrip("VIN", 100.0, null, null)
        now += 3_600_000
        val ended = trips.endTrip(trip.copy(startTime = 0, startOdometer = 0.0, vin = "OTHER"), 125.0, null, null)
        assertEquals(trip.startTime, ended.startTime)
        assertEquals(trip.startOdometer, ended.startOdometer)
        assertEquals("VIN", ended.vin)
        assertEquals(25.0, ended.distanceKm)
        assertEquals(25.0, ended.avgSpeedKmph)
    }

    @Test
    fun `backwards clock cannot end a trip before it started`() = runTest {
        val trip = trips.startTrip("VIN", 0.0, null, null)
        now--
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { trips.endTrip(trip, 1.0, null, null) }
        }
        assertEquals(trip, trips.getTrip(trip.id!!))
    }

    @Test
    fun `zero duration trip has zero average speed`() = runTest {
        val trip = trips.startTrip("VIN", 0.0, null, null)
        val ended = trips.endTrip(trip, 0.0, null, null)
        assertEquals(0.0, ended.avgSpeedKmph)
        assertEquals(0L, ended.durationSeconds)
    }

    private fun reminder(date: Long?, odometer: Double?, completed: Boolean = false) = ServiceReminder(
        vin = "VIN", title = "Service", description = null,
        dueOdometerKm = odometer, dueDateMillis = date,
        completed = completed, createdAtMillis = 0,
    )

    @Test
    fun `reminders are due at either threshold but not without thresholds or after completion`() = runTest {
        val cases = listOf(
            reminder(10_000, 500.0) to true,
            reminder(20_000, 100.0) to true,
            reminder(20_000, 500.0) to false,
            reminder(10_000, null) to true,
            reminder(null, 100.0) to true,
            reminder(null, null) to false,
            reminder(10_000, 100.0, completed = true) to false,
            reminder(0, 0.0) to true,
            reminder(20_000, null) to false,
            reminder(null, 500.0) to false,
        )
        val expected = mutableSetOf<Long?>()
        for ((reminder, due) in cases) {
            assertEquals(due, reminder.isDue(100.0, 10_000), reminder.toString())
            val saved = reminders.addReminder(reminder)
            if (due) expected.add(saved.id)
        }
        reminders.addReminder(reminder(0, 0.0).copy(vin = "OTHER"))
        assertEquals(expected, reminders.getDueReminders("VIN", 100.0, 10_000).map { it.id }.toSet())
        for (id in expected) reminders.completeReminder(id!!, 10_001)
        assertTrue(reminders.getDueReminders("VIN", 100.0, 10_000).isEmpty())
    }
}
