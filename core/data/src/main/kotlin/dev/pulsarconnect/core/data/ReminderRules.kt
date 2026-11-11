package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.ServiceReminder
import java.time.Instant
import java.time.ZoneId

/**
 * Validates the rider-editable fields of a reminder. Kept separate from insertion so existing
 * records that predate validation can still be read and completed.
 */
fun requireValidReminder(reminder: ServiceReminder) {
    require(reminder.title.isNotBlank()) { "Enter a reminder title." }
    require(reminder.dueOdometerKm != null || reminder.dueDateMillis != null) {
        "Enter a due odometer or date (or both)."
    }
    reminder.dueOdometerKm?.let { odometer ->
        require(odometer.isFinite() && odometer >= 0) {
            "Enter a finite, nonnegative odometer reading in km."
        }
    }
}

/** Shifts whichever thresholds are set without touching completion state. */
fun ServiceReminder.snoozed(days: Long, kilometres: Double, zone: ZoneId): ServiceReminder {
    require(days >= 0 && kilometres.isFinite() && kilometres >= 0) {
        "Snooze must move the reminder forward"
    }
    val shiftedOdometer = dueOdometerKm?.let { it + kilometres }
    require(shiftedOdometer == null || shiftedOdometer.isFinite()) {
        "Snoozed odometer must be finite"
    }
    return copy(
        dueDateMillis = dueDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(zone).plusDays(days).toInstant().toEpochMilli()
        },
        dueOdometerKm = shiftedOdometer,
    )
}
