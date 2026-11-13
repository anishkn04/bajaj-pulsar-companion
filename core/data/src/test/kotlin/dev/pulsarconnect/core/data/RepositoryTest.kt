package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryTest {

    private var now = 1_000_000L
    private val tripRepo = InMemoryTripRepository(clock = { now })
    private val reminderRepo = InMemoryReminderRepository()
    private val bikeRepo = InMemoryBikeRepository()

    @Test
    fun `trip lifecycle start add telemetry end`() = runTest {
        val trip = tripRepo.startTrip("MD2ABCDEFGH123456", 1000.0, 12.9716, 77.5946)

        assertNotNull(trip.id)
        assertEquals(1000.0, trip.startOdometer)
        assertNull(trip.endTime)

        // Add some telemetry
        tripRepo.addTelemetry(trip.id!!, TelemetryPoint(
            tripId = trip.id!!,
            timestamp = now,
            speedKmph = 40.0,
            rpm = 3000,
            fuelLevelPercent = 80,
            engineTempC = 90,
            lat = 12.9716,
            lon = 77.5946,
            synced = false,
        ))
        tripRepo.addTelemetry(trip.id!!, TelemetryPoint(
            tripId = trip.id!!,
            timestamp = now + 1000,
            speedKmph = 50.0,
            rpm = 3500,
            fuelLevelPercent = 79,
            engineTempC = 92,
            lat = 12.9720,
            lon = 77.5950,
            synced = false,
        ))

        val telemetry = tripRepo.getTelemetry(trip.id!!)
        assertEquals(2, telemetry.size)

        now += 3_600_000
        val ended = tripRepo.endTrip(trip, 1015.5, 12.9720, 77.5950)

        assertEquals(1015.5, ended.endOdometer)
        assertEquals(15.5, ended.distanceKm)
        assertNotNull(ended.endTime)
        assertEquals(15.5, ended.avgSpeedKmph)

        // Fetch back
        val fetched = tripRepo.getTrip(ended.id!!)
        assertEquals(ended.distanceKm, fetched?.distanceKm)
    }

    @Test
    fun `home aggregates include only completed trips for the requested bike`() = runTest {
        val vin = "MD2ABCDEFGH123456"
        val other = "MD2ABCDEFGH654321"
        val first = tripRepo.startTrip(vin, 100.0, null, null)
        now += 3_600_000
        tripRepo.endTrip(first, 140.0, null, null)
        val second = tripRepo.startTrip(vin, 140.0, null, null)
        now += 3_600_000
        tripRepo.endTrip(second, 200.0, null, null)
        val foreign = tripRepo.startTrip(other, 0.0, null, null)
        now += 3_600_000
        tripRepo.endTrip(foreign, 60.0, null, null)
        tripRepo.startTrip(vin, 500.0, null, null)

        assertEquals(100.0, tripRepo.totalDistanceKm(vin))
        assertEquals(2, tripRepo.completedTripsBetween(vin, 0, Long.MAX_VALUE))
        assertEquals(0, tripRepo.completedTripsBetween(vin, now + 1, Long.MAX_VALUE))
    }

    @Test
    fun `bike profile upsert and fetch`() = runTest {
        val profile = BikeProfile(
            vin = "MD2ABCDEFGH123456",
            model = "PULSAR 150 N DISC ES",
            nickname = "My Pulsar",
            modelImageUrl = null,
            purchaseDateMillis = null,
            lastSyncedAtMillis = null,
        )

        bikeRepo.upsertBike(profile)
        val fetched = bikeRepo.getBike(profile.vin)

        assertEquals("My Pulsar", fetched?.displayName)
        assertEquals("PULSAR 150 N DISC ES", fetched?.model)
    }

    @Test
    fun `reminder creation and due filtering`() = runTest {
        val now = this@RepositoryTest.now
        val reminder = ServiceReminder(
            vin = "MD2ABCDEFGH123456",
            title = "Oil Change",
            description = null,
            dueOdometerKm = 5000.0,
            dueDateMillis = now + 86_400_000, // tomorrow
            completed = false,
            completedAtMillis = null,
            createdAtMillis = now,
        )

        val created = reminderRepo.addReminder(reminder)
        assertNotNull(created.id)

        val due = reminderRepo.getDueReminders("MD2ABCDEFGH123456", 5500.0, now + 100_000_000)
        assertEquals(1, due.size)
        assertEquals("Oil Change", due.first().title)

        // Complete it
        reminderRepo.completeReminder(created.id!!, now)

        val dueAfterComplete = reminderRepo.getDueReminders("MD2ABCDEFGH123456", 5500.0, now + 100_000_000)
        assertTrue(dueAfterComplete.isEmpty())
    }

    @Test
    fun `multiple trips for same vin ordered by time desc`() = runTest {
        val vin = "MD2ABCDEFGH123456"
        val t1 = tripRepo.startTrip(vin, 1000.0, null, null)
        now++
        val t2 = tripRepo.startTrip(vin, 2000.0, null, null)
        now++
        val t3 = tripRepo.startTrip(vin, 3000.0, null, null)

        tripRepo.endTrip(t1, 1010.0, null, null)
        tripRepo.endTrip(t2, 2020.0, null, null)
        tripRepo.endTrip(t3, 3030.0, null, null)

        val trips = tripRepo.getTrips(vin, 10, 0)
        assertEquals(3, trips.size)
        assertEquals(t3.id, trips[0].id) // most recent first
        assertEquals(t2.id, trips[1].id)
        assertEquals(t1.id, trips[2].id)
    }

    @Test
    fun `filtered trip pages apply date bounds before limit and offset`() = runTest {
        val vin = "MD2ABCDEFGH123456"
        val base = 1_000_000L
        val day = 86_400_000L
        val starts = (0 until 5).map { base + it * day }
        starts.forEachIndexed { index, start ->
            now = start
            val trip = tripRepo.startTrip(vin, index * 10.0, null, null)
            now = start + 1_000
            tripRepo.endTrip(trip, index * 10.0 + 5.0, null, null)
        }

        val from = starts[1]
        val to = starts[4]
        assertEquals(
            listOf(starts[3], starts[2]),
            tripRepo.getTripsBetween(vin, from, to, 2, 0).map { it.startTime },
        )
        assertEquals(
            listOf(starts[1]),
            tripRepo.getTripsBetween(vin, from, to, 2, 2).map { it.startTime },
        )
        assertEquals(
            listOf(starts[4], starts[3]),
            tripRepo.getTripsBetween(vin, starts[3], null, 10, 0).map { it.startTime },
        )
        assertEquals(5, tripRepo.getTripsBetween(vin, null, null, 10, 0).size)
        val reversed = runCatching { tripRepo.getTripsBetween(vin, to, from, 10, 0) }.exceptionOrNull()
        assertTrue(reversed is IllegalArgumentException, reversed?.message)
    }

    @Test
    fun `manual odometer readings are monotonic per bike`() = runTest {
        val store = InMemoryOdometerRepository()
        val vin = "MD2ABCDEFGH123456"

        assertNull(store.getLatest(vin))
        store.setReading(vin, 1_200.0, 5)
        assertEquals(1_200.0, store.getLatest(vin)!!.odometerKm, 0.0)
        val failure = runCatching { store.setReading(vin, 1_199.0, 6) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, failure?.message)
        assertEquals(1_200.0, store.getLatest(vin)!!.odometerKm, 0.0)
        store.setReading("MD2ABCDEFGH654321", 10.0, 7)
        assertEquals(10.0, store.getLatest("MD2ABCDEFGH654321")!!.odometerKm, 0.0)
    }

    @Test
    fun `latest odometer uses the highest of a manual reading and trip history`() = runTest {
        val vin = "MD2ABCDEFGH123456"
        val odometers = InMemoryOdometerRepository()

        assertNull(latestOdometerKm(vin, tripRepo, odometers))
        val trip = tripRepo.startTrip(vin, 1_000.0, null, null)
        assertEquals(1_000.0, latestOdometerKm(vin, tripRepo, odometers)!!, 0.0)

        odometers.setReading(vin, 900.0, 1)
        assertEquals(1_000.0, latestOdometerKm(vin, tripRepo, odometers)!!, 0.0)
        odometers.setReading(vin, 1_200.0, 2)
        assertEquals(1_200.0, latestOdometerKm(vin, tripRepo, odometers)!!, 0.0)

        now += 3_600_000
        tripRepo.endTrip(trip, 1_500.0, null, null)
        assertEquals(1_500.0, latestOdometerKm(vin, tripRepo, odometers)!!, 0.0)
    }
}
