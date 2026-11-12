package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.Trip
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripShareTest {
    private val zone = ZoneId.of("UTC")

    private fun trip(
        endTime: Long? = 3_600_000,
        endOdometer: Double? = 1_250.0,
        avgSpeed: Double? = 30.0,
        maxSpeed: Double? = 48.0,
        startLat: Double? = 12.9716,
        startLon: Double? = 77.5946,
        endLat: Double? = 12.9352,
        endLon: Double? = 77.6245,
    ) = Trip(
        id = 7,
        vin = "MD2ABCDEFGH123456",
        startTime = 0,
        endTime = endTime,
        startOdometer = 1_000.0,
        endOdometer = endOdometer,
        distanceKm = 250.0,
        avgSpeedKmph = avgSpeed,
        maxSpeedKmph = maxSpeed,
        fuelUsedLiters = null,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
    )

    @Test
    fun `share text includes the stored ride values and a maps link`() {
        val text = tripShareText(trip(), zone, Locale.US)
        assertTrue(text.contains("Distance: 250 km"), text)
        assertTrue(text.contains("Duration: 1 h 0 min"), text)
        assertTrue(text.contains("Average speed: 30 km/h"), text)
        assertTrue(text.contains("Maximum speed: 48 km/h"), text)
        assertTrue(text.contains("Odometer: 1,000 to 1,250 km"), text)
        assertTrue(text.contains("Started at: 12.97160, 77.59460"), text)
        assertTrue(text.contains("Ended at: 12.93520, 77.62450"), text)
        assertTrue(text.contains("Map: geo:12.9352,77.6245?q=12.9352,77.6245"), text)
    }

    @Test
    fun `share text never contains the VIN or a Bluetooth address`() {
        val text = tripShareText(trip(), zone, Locale.US)
        assertFalse(text.contains("MD2ABCDEFGH123456"), text)
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"), text)
        assertFalse(text.contains("vin", ignoreCase = true), text)
    }

    @Test
    fun `missing values are omitted instead of rendered as placeholders`() {
        val text = tripShareText(
            trip(
                endTime = null, endOdometer = null, avgSpeed = null, maxSpeed = null,
                startLat = null, startLon = null, endLat = null, endLon = null,
            ),
            zone, Locale.US,
        )
        assertFalse(text.contains("undefined"), text)
        assertFalse(text.contains("Average speed"), text)
        assertFalse(text.contains("Maximum speed"), text)
        assertFalse(text.contains("Odometer"), text)
        assertFalse(text.contains("Map:"), text)
        assertFalse(text.contains("Duration"), text)
        assertTrue(text.contains("Distance: 250 km"), text)
    }

    @Test
    fun `distance and duration use a single line each`() {
        val text = tripShareText(trip(), zone, Locale.US)
        assertEquals(1, text.lines().count { it.startsWith("Distance:") })
        assertEquals(1, text.lines().count { it.startsWith("Duration:") })
        assertEquals(1, text.lines().count { it.startsWith("Map:") })
    }
}
