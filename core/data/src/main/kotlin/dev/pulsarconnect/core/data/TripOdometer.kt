package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.Trip

/** Requires a finite, nonnegative odometer reading. */
fun requireValidOdometer(odometer: Double) {
    require(odometer.isFinite() && odometer >= 0) { "Odometer must be finite and nonnegative" }
}

/**
 * Returns a completed trip with corrected odometer readings. The stored VIN and times are kept;
 * distance and average speed are recomputed from the corrected readings and the stored duration.
 *
 * Throws [IllegalArgumentException] for an unfinished trip, an invalid reading, or an end value
 * below the start value.
 */
fun Trip.correctedOdometer(startOdometerKm: Double, endOdometerKm: Double): Trip {
    val endedAt = requireNotNull(endTime) { "Only completed trips can be edited" }
    requireValidOdometer(startOdometerKm)
    requireValidOdometer(endOdometerKm)
    require(endOdometerKm >= startOdometerKm) { "End odometer must not precede start odometer" }
    val distance = endOdometerKm - startOdometerKm
    val durationHours = (endedAt - startTime) / 3_600_000.0
    return copy(
        startOdometer = startOdometerKm,
        endOdometer = endOdometerKm,
        distanceKm = distance,
        avgSpeedKmph = if (durationHours > 0) distance / durationHours else 0.0,
    )
}
