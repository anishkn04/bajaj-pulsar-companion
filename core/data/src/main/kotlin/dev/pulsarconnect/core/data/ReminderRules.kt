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
    reminder.repeatEveryKm?.let { interval ->
        require(interval.isFinite() && interval > 0) {
            "Enter a repeat distance greater than 0 km."
        }
    }
    reminder.repeatEveryDays?.let { interval ->
        require(interval > 0) { "Enter a repeat interval of at least one day." }
    }
    val repeats = reminder.repeatEveryKm != null || reminder.repeatEveryDays != null
    if (repeats) {
        require((reminder.dueOdometerKm == null) == (reminder.repeatEveryKm == null)) {
            "Set a repeat distance only when the reminder has a due odometer."
        }
        require((reminder.dueDateMillis == null) == (reminder.repeatEveryDays == null)) {
            "Set a repeat number of days only when the reminder has a due date."
        }
    }
}

/**
 * Builds the single next occurrence of a recurring reminder. Thresholds without a matching repeat
 * interval are dropped so the new occurrence cannot be immediately due by a stale threshold.
 * Returns null for one-off reminders.
 */
fun ServiceReminder.nextOccurrence(atMillis: Long): ServiceReminder? {
    val nextOdometer = repeatEveryKm?.let { interval ->
        dueOdometerKm?.let { due -> (due + interval).takeIf(Double::isFinite) }
    }
    val nextDate = repeatEveryDays?.let { interval ->
        dueDateMillis?.let { due -> due + interval * MILLIS_PER_DAY }
    }
    if (nextOdometer == null && nextDate == null) return null
    return copy(
        id = null,
        dueOdometerKm = nextOdometer,
        dueDateMillis = nextDate,
        completed = false,
        completedAtMillis = null,
        createdAtMillis = atMillis,
    )
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

private const val MILLIS_PER_DAY = 86_400_000L
