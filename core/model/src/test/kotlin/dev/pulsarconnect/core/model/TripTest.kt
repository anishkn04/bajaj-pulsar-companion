package dev.pulsarconnect.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TripTest {
    private fun trip(endTime: Long?) = Trip(
        vin = "VIN", startTime = 10_000, endTime = endTime,
        startOdometer = 0.0, endOdometer = null, distanceKm = null,
        avgSpeedKmph = null, maxSpeedKmph = null, fuelUsedLiters = null,
        startLat = null, startLon = null, endLat = null, endLon = null,
    )

    @Test
    fun `duration uses whole seconds rather than milliseconds`() {
        assertEquals(3L, trip(13_999).durationSeconds)
        assertEquals(0L, trip(10_999).durationSeconds)
    }

    @Test
    fun `active and backwards durations are zero`() {
        assertEquals(0L, trip(null).durationSeconds)
        assertEquals(0L, trip(8_000).durationSeconds)
    }
}
