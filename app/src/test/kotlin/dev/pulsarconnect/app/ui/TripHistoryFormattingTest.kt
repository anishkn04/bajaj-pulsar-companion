package dev.pulsarconnect.app.ui

import dev.pulsarconnect.core.model.Trip
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripHistoryFormattingTest {
    private fun trip(start: String) = Trip(
        vin = "MD2ABCDEFGH123456",
        startTime = Instant.parse(start).toEpochMilli(),
        endTime = Instant.parse(start).toEpochMilli() + 1_800_000,
        startOdometer = 0.0,
        endOdometer = 5.0,
        distanceKm = 5.0,
        avgSpeedKmph = null,
        maxSpeedKmph = null,
        fuelUsedLiters = null,
        startLat = null,
        startLon = null,
        endLat = null,
        endLon = null,
    )

    @Test
    fun `month headers appear once per calendar month in the device zone`() {
        val trips = listOf(
            trip("2026-12-02T10:00:00Z"),
            trip("2026-12-01T10:00:00Z"),
            trip("2026-11-30T10:00:00Z"),
            trip("2026-11-01T10:00:00Z"),
        )

        assertEquals("December 2026", monthHeaderLabel(trips, 0, ZoneOffset.UTC, Locale.US))
        assertNull(monthHeaderLabel(trips, 1, ZoneOffset.UTC, Locale.US))
        assertEquals("November 2026", monthHeaderLabel(trips, 2, ZoneOffset.UTC, Locale.US))
        assertNull(monthHeaderLabel(trips, 3, ZoneOffset.UTC, Locale.US))
        assertNull(monthHeaderLabel(emptyList(), 0, ZoneOffset.UTC, Locale.US))
        assertNull(monthHeaderLabel(trips, 4, ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun `month grouping follows the local zone rather than UTC`() {
        val trips = listOf(
            trip("2026-12-01T00:30:00Z"),
            trip("2026-11-30T20:00:00Z"),
        )

        assertEquals("December 2026", monthHeaderLabel(trips, 0, ZoneOffset.UTC, Locale.US))
        assertEquals("November 2026", monthHeaderLabel(trips, 1, ZoneOffset.UTC, Locale.US))
        assertEquals(
            "December 2026",
            monthHeaderLabel(trips, 0, ZoneId.of("Asia/Kolkata"), Locale.US),
        )
        assertNull(monthHeaderLabel(trips, 1, ZoneId.of("Asia/Kolkata"), Locale.US))
    }

    @Test
    fun `filter summary describes inclusive bounds`() {
        val from = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        val toExclusive = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

        assertEquals(
            "Showing trips from 2026-10-01 to 2026-10-31.",
            tripFilterSummary(from, toExclusive, ZoneOffset.UTC),
        )
        assertEquals(
            "Showing trips from 2026-10-01 onward.",
            tripFilterSummary(from, null, ZoneOffset.UTC),
        )
        assertEquals(
            "Showing trips through 2026-10-31.",
            tripFilterSummary(null, toExclusive, ZoneOffset.UTC),
        )
        assertNull(tripFilterSummary(null, null, ZoneOffset.UTC))
    }
}
