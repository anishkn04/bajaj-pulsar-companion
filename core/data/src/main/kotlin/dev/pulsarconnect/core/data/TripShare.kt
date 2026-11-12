package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.Trip
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Plain-text summary of a stored trip for the Android share sheet.
 *
 * Deliberately omits the VIN, the stored Bluetooth device, and anything else that identifies
 * the bike: the text leaves the app and may be pasted anywhere. The map entry is a `geo:` URI
 * that the receiving app can open with an installed maps app, so no network lookup or API key
 * is involved here.
 */
fun tripShareText(trip: Trip, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)

    fun format(value: Double): String = number.format(value)
    fun coordinates(lat: Double?, lon: Double?): String? =
        if (lat != null && lon != null) String.format(locale, "%.5f, %.5f", lat, lon) else null

    val lines = mutableListOf(
        "Pulsar Connect trip",
        "Started: ${dateTime.format(Instant.ofEpochMilli(trip.startTime))}",
    )
    trip.endTime?.let { lines += "Ended: ${dateTime.format(Instant.ofEpochMilli(it))}" }
    trip.durationSeconds.takeIf { it > 0 }?.let { seconds ->
        val duration = Duration.ofSeconds(seconds)
        lines += "Duration: ${duration.toHours()} h ${duration.toMinutes() % 60} min"
    }
    trip.distanceKm?.let { lines += "Distance: ${format(it)} km" }
    trip.avgSpeedKmph?.let { lines += "Average speed: ${format(it)} km/h" }
    trip.maxSpeedKmph?.let { lines += "Maximum speed: ${format(it)} km/h" }
    trip.endOdometer?.let {
        lines += "Odometer: ${format(trip.startOdometer)} to ${format(it)} km"
    }
    coordinates(trip.startLat, trip.startLon)?.let { lines += "Started at: $it" }
    coordinates(trip.endLat, trip.endLon)?.let { lines += "Ended at: $it" }

    val mapLat = trip.endLat ?: trip.startLat
    val mapLon = trip.endLon ?: trip.startLon
    if (mapLat != null && mapLon != null) {
        lines += "Map: geo:$mapLat,$mapLon?q=$mapLat,$mapLon"
    }
    return lines.joinToString("\n")
}
