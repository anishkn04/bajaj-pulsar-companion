package dev.pulsarconnect.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Trip(
    val id: Long? = null,
    val vin: String,
    val startTime: Long,
    val endTime: Long?,
    val startOdometer: Double,
    val endOdometer: Double?,
    val distanceKm: Double?,
    val avgSpeedKmph: Double?,
    val maxSpeedKmph: Double?,
    val fuelUsedLiters: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val synced: Boolean = false,
) {
    val durationSeconds: Long
        get() = endTime?.let { ((it - startTime) / 1_000).coerceAtLeast(0) } ?: 0
}

@Serializable
data class TelemetryPoint(
    val id: Long? = null,
    val tripId: Long,
    val timestamp: Long,
    val speedKmph: Double?,
    val rpm: Int?,
    val fuelLevelPercent: Int?,
    val engineTempC: Int?,
    val lat: Double?,
    val lon: Double?,
    val synced: Boolean = false,
)

@Serializable
data class ServiceReminder(
    val id: Long? = null,
    val vin: String,
    val title: String,
    val description: String?,
    val dueOdometerKm: Double?,
    val dueDateMillis: Long?,
    val completed: Boolean = false,
    val completedAtMillis: Long? = null,
    val createdAtMillis: Long,
)

@Serializable
data class BikeProfile(
    val vin: String,
    val model: String?,
    val modelImageUrl: String?,
    val nickname: String?,
    val purchaseDateMillis: Long?,
    val lastSyncedAtMillis: Long? = null,
) {
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: model ?: "Pulsar"
}
