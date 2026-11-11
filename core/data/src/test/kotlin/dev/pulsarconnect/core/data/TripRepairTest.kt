package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.TelemetryPoint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class TripRepairTest {

    private var now = 1_000_000L
    private val trips = InMemoryTripRepository(clock = { now })

    private fun point(tripId: Long, timestamp: Long = 0) = TelemetryPoint(
        tripId = tripId, timestamp = timestamp, speedKmph = 40.0, rpm = 2_000,
        fuelLevelPercent = 50, engineTempC = 80, lat = null, lon = null, synced = false,
    )

    private suspend fun completedTrip(vin: String = "DEV-BIKE-A", start: Double = 100.0, end: Double = 150.0) =
        trips.startTrip(vin, start, null, null).let { trip ->
            now += 3_600_000
            trips.endTrip(trip, end, null, null)
        }

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected repository contract failure.
        }
    }

    @Test
    fun `editing recomputes distance and average speed from the stored duration`() = runTest {
        val trip = completedTrip(start = 100.0, end = 150.0)
        now += 7_200_000

        val updated = trips.updateTripOdometer(trip.id!!, 90.0, 140.0)

        assertEquals(90.0, updated.startOdometer)
        assertEquals(140.0, updated.endOdometer)
        assertEquals(50.0, updated.distanceKm)
        assertEquals(50.0, updated.avgSpeedKmph)
        assertEquals(trip.startTime, updated.startTime)
        assertEquals(trip.endTime, updated.endTime)
        assertEquals(trip.vin, updated.vin)
        assertEquals(updated, trips.getTrip(trip.id!!))
    }

    @Test
    fun `editing rejects active trips and invalid readings without changing the stored trip`() = runTest {
        val active = trips.startTrip("DEV-BIKE-A", 100.0, null, null)
        rejects { trips.updateTripOdometer(active.id!!, 100.0, 120.0) }

        val trip = completedTrip()
        val invalid = listOf(
            Triple(-1.0, 150.0, "negative start"),
            Triple(100.0, -1.0, "negative end"),
            Triple(Double.NaN, 150.0, "NaN start"),
            Triple(100.0, Double.POSITIVE_INFINITY, "infinite end"),
            Triple(200.0, 150.0, "end before start"),
        )
        for ((start, end, label) in invalid) {
            try {
                trips.updateTripOdometer(trip.id!!, start, end)
                fail("Expected IllegalArgumentException for $label")
            } catch (_: IllegalArgumentException) {
                // Expected repository contract failure.
            }
        }
        assertEquals(trip, trips.getTrip(trip.id!!))
    }

    @Test
    fun `editing a missing trip fails`() = runTest {
        rejects { trips.updateTripOdometer(999, 0.0, 1.0) }
    }

    @Test
    fun `deleting removes the trip and its telemetry but leaves other trips intact`() = runTest {
        val first = completedTrip()
        val second = completedTrip(start = 200.0, end = 240.0)
        trips.addTelemetry(first.id!!, point(first.id!!))
        trips.addTelemetry(second.id!!, point(second.id!!))

        trips.deleteTrip(first.id!!)

        assertNull(trips.getTrip(first.id!!))
        assertTrue(trips.getTelemetry(first.id!!).isEmpty())
        assertEquals(second, trips.getTrip(second.id!!))
        assertEquals(1, trips.getTelemetry(second.id!!).size)
        assertEquals(listOf(second), trips.getTrips("DEV-BIKE-A", 10, 0))
    }

    @Test
    fun `deleting a missing trip fails`() = runTest {
        rejects { trips.deleteTrip(404) }
    }

    @Test
    fun `undo restore reinserts the original trip and keeps new ids unique`() = runTest {
        val trip = completedTrip()
        trips.deleteTrip(trip.id!!)

        val restored = trips.restoreTrip(trip)

        assertEquals(trip, restored)
        assertEquals(trip, trips.getTrip(trip.id!!))
        assertTrue(trips.getTelemetry(trip.id!!).isEmpty())

        val next = trips.startTrip("DEV-BIKE-A", 500.0, null, null)
        assertTrue(next.id!! > trip.id!!)
    }

    @Test
    fun `restore rejects a duplicate id`() = runTest {
        val trip = completedTrip()
        rejects { trips.restoreTrip(trip) }
    }
}
