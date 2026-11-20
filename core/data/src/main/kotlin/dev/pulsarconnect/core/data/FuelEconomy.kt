package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.FuelFillUp

/** Requires a nonblank VIN, a finite nonnegative odometer, and finite positive litres. */
fun requireValidFillUp(fillUp: FuelFillUp) {
    require(fillUp.vin.isNotBlank()) { "Fill-up must belong to a bike" }
    requireValidOdometer(fillUp.odometerKm)
    require(fillUp.liters.isFinite() && fillUp.liters > 0) {
        "Litres must be finite and greater than 0"
    }
}

/** One full-to-full economy interval. [liters] includes any partial fill-ups in between. */
data class FuelInterval(
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val distanceKm: Double,
    val liters: Double,
) {
    val lPer100Km: Double get() = liters / distanceKm * 100
    val kmPerLitre: Double get() = distanceKm / liters
}

/**
 * Closed full-to-full intervals, oldest first. A bike with fewer than two full-tank fill-ups has
 * no intervals yet, which the UI must show as "not enough data" rather than zero consumption.
 */
data class FuelEconomy(
    val intervals: List<FuelInterval>,
) {
    val latest: FuelInterval? get() = intervals.lastOrNull()
    val totalDistanceKm: Double get() = intervals.sumOf { it.distanceKm }
    val totalLiters: Double get() = intervals.sumOf { it.liters }
    val averageLPer100Km: Double?
        get() = if (totalDistanceKm > 0) totalLiters / totalDistanceKm * 100 else null
    val averageKmPerLitre: Double?
        get() = if (totalLiters > 0) totalDistanceKm / totalLiters else null
}

/**
 * Computes full-to-full fuel economy from manual fill-ups.
 *
 * The first full-tank fill-up is only a baseline. Each later full-tank fill-up closes an
 * interval, and every fill-up in between contributes its litres, so partial fills improve
 * accuracy instead of breaking the calculation. Partial fills before the first full tank are
 * ignored because there is no known full starting point.
 */
fun fuelEconomy(fillUps: List<FuelFillUp>): FuelEconomy {
    fillUps.forEach(::requireValidFillUp)
    val sorted = fillUps.sortedWith(
        compareBy<FuelFillUp> { it.odometerKm }.thenBy { it.id ?: Long.MAX_VALUE },
    )
    val intervals = mutableListOf<FuelInterval>()
    var baseline: FuelFillUp? = null
    var litersSinceBaseline = 0.0
    for (fillUp in sorted) {
        val start = baseline
        if (start == null) {
            if (fillUp.fullTank) baseline = fillUp
            continue
        }
        litersSinceBaseline += fillUp.liters
        if (!fillUp.fullTank) continue
        val distance = fillUp.odometerKm - start.odometerKm
        if (distance > 0 && litersSinceBaseline > 0) {
            intervals += FuelInterval(
                startOdometerKm = start.odometerKm,
                endOdometerKm = fillUp.odometerKm,
                distanceKm = distance,
                liters = litersSinceBaseline,
            )
        }
        baseline = fillUp
        litersSinceBaseline = 0.0
    }
    return FuelEconomy(intervals)
}

/** Highest odometer recorded by any fill-up for this bike, or null when there are none. */
fun latestFillUpOdometerKm(fillUps: List<FuelFillUp>): Double? =
    fillUps.maxOfOrNull { it.odometerKm }
