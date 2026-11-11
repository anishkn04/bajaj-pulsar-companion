package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.ServiceReminder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SqliteTripReminderRegressionTest {
    private val file = java.io.File.createTempFile("repository_", ".db")
    private val db = Database.create(file.absolutePath)

    init {
        runBlocking {
            for (vin in listOf("VIN", "OTHER")) {
                db.upsertBike(dev.pulsarconnect.core.model.BikeProfile(vin, null, null, null, null))
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        db.close()
        file.delete()
    }

    private var now = 10_000L
    private val trips: TripRepository = SqliteTripRepository(db, clock = { now })
    private val reminders: ReminderRepository = SqliteReminderRepository(db)

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

    @Test
    fun `editing a completed trip recomputes distance and keeps stored times`() = runTest {
        val trip = trips.startTrip("VIN", 100.0, null, null)
        now += 3_600_000
        val ended = trips.endTrip(trip, 150.0, null, null)

        val updated = trips.updateTripOdometer(ended.id!!, 90.0, 140.0)

        assertEquals(90.0, updated.startOdometer)
        assertEquals(140.0, updated.endOdometer)
        assertEquals(50.0, updated.distanceKm)
        assertEquals(50.0, updated.avgSpeedKmph)
        assertEquals(ended.startTime, updated.startTime)
        assertEquals(ended.endTime, updated.endTime)
        assertEquals("VIN", updated.vin)
        assertEquals(updated, trips.getTrip(ended.id!!))
    }

    @Test
    fun `editing rejects active trips missing trips and invalid readings`() = runTest {
        val active = trips.startTrip("VIN", 100.0, null, null)
        for ((start, end) in listOf(100.0 to 120.0, -1.0 to 150.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { trips.updateTripOdometer(active.id!!, start, end) }
            }
        }
        assertEquals(active, trips.getTrip(active.id!!))

        now += 1_000
        val ended = trips.endTrip(active, 150.0, null, null)
        for ((start, end) in listOf(
            -1.0 to 150.0,
            100.0 to -1.0,
            Double.NaN to 150.0,
            100.0 to Double.POSITIVE_INFINITY,
            200.0 to 150.0,
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { trips.updateTripOdometer(ended.id!!, start, end) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { trips.updateTripOdometer(Long.MAX_VALUE, 0.0, 1.0) }
        }
        assertEquals(ended, trips.getTrip(ended.id!!))
    }

    @Test
    fun `deleting removes the trip and telemetry and undo restores the original id`() = runTest {
        val trip = trips.startTrip("VIN", 100.0, null, null)
        now += 3_600_000
        val ended = trips.endTrip(trip, 150.0, null, null)
        trips.addTelemetry(ended.id!!, point(ended.id!!))
        val other = trips.startTrip("OTHER", 0.0, null, null)

        trips.deleteTrip(ended.id!!)

        assertNull(trips.getTrip(ended.id!!))
        assertTrue(trips.getTelemetry(ended.id!!).isEmpty())
        assertEquals(other, trips.getTrip(other.id!!))

        assertEquals(ended, trips.restoreTrip(ended))
        assertEquals(ended, trips.getTrip(ended.id!!))
        assertTrue(trips.getTelemetry(ended.id!!).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { trips.restoreTrip(ended) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { trips.deleteTrip(Long.MAX_VALUE) }
        }
    }

    private fun point(tripId: Long) = dev.pulsarconnect.core.model.TelemetryPoint(
        tripId = tripId, timestamp = 0, speedKmph = 40.0, rpm = 2_000,
        fuelLevelPercent = 50, engineTempC = 80, lat = null, lon = null, synced = false,
    )

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

    @Test
    fun `editing a reminder replaces rider fields and persists completion`() = runTest {
        val saved = reminders.addReminder(reminder(10_000, 500.0))
        reminders.completeReminder(saved.id!!, 12_000)

        val updated = reminders.updateReminder(reminders.getReminders("VIN").single().copy(
            title = "Chain",
            description = "Clean and lube",
            dueOdometerKm = 800.0,
            dueDateMillis = 30_000,
        ))

        assertEquals("Chain", updated.title)
        assertEquals("Clean and lube", updated.description)
        assertEquals(800.0, updated.dueOdometerKm)
        assertEquals(30_000L, updated.dueDateMillis)
        assertTrue(updated.completed)
        assertEquals(12_000L, updated.completedAtMillis)
        assertEquals(updated, reminders.getReminders("VIN").single())
    }

    @Test
    fun `editing rejects invalid or missing reminders and leaves stored data intact`() = runTest {
        val saved = reminders.addReminder(reminder(10_000, 500.0))
        for (invalid in listOf(
            saved.copy(title = " "),
            saved.copy(dueOdometerKm = null, dueDateMillis = null),
            saved.copy(dueOdometerKm = -1.0),
            saved.copy(dueOdometerKm = Double.NaN),
            saved.copy(id = Long.MAX_VALUE),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reminders.updateReminder(invalid) }
            }
        }
        assertEquals(saved, reminders.getReminders("VIN").single())
    }

    @Test
    fun `deleting removes only the selected reminder and reopening makes it due again`() = runTest {
        val first = reminders.addReminder(reminder(10_000, 100.0))
        val second = reminders.addReminder(reminder(20_000, 200.0))

        reminders.deleteReminder(first.id!!)
        assertEquals(listOf(second), reminders.getReminders("VIN"))

        reminders.completeReminder(second.id!!, 12_000)
        assertTrue(reminders.getDueReminders("VIN", 500.0, 25_000).isEmpty())
        val reopened = reminders.reopenReminder(second.id!!)
        assertFalse(reopened.completed)
        assertNull(reopened.completedAtMillis)
        assertEquals(listOf(reopened), reminders.getDueReminders("VIN", 500.0, 25_000))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reminders.deleteReminder(first.id!!) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reminders.reopenReminder(first.id!!) }
        }
    }
}
