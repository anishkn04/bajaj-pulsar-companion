package dev.pulsarconnect.app.ui

import dev.pulsarconnect.core.data.isDue
import dev.pulsarconnect.core.model.ServiceReminder
import java.time.Instant
import java.time.ZoneId

/** First and last millisecond of the calendar month containing [nowMillis]. */
internal fun monthRange(nowMillis: Long, zone: ZoneId): Pair<Long, Long> {
    val month = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().withDayOfMonth(1)
    val start = month.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

/**
 * The reminder the home card should surface: anything already due takes priority, otherwise
 * the closest upcoming date, then the closest upcoming odometer threshold.
 */
internal fun nextPendingReminder(
    reminders: List<ServiceReminder>,
    lastOdometer: Double?,
    now: Long,
): ServiceReminder? {
    val pending = reminders.filter { !it.completed }
    if (pending.isEmpty()) return null
    val odometer = lastOdometer ?: Double.NaN
    val due = pending.filter { it.isDue(odometer, now) }
    return (due.ifEmpty { pending }).minWithOrNull(
        compareBy<ServiceReminder> { it.dueDateMillis ?: Long.MAX_VALUE }
            .thenBy { it.dueOdometerKm ?: Double.MAX_VALUE },
    )
}

/** Short, threshold-based description for the home stats card. */
internal fun reminderTimingLabel(reminder: ServiceReminder, now: Long): String {
    val parts = buildList {
        reminder.dueDateMillis?.let { due ->
            add(if (due <= now) "date passed" else "on ${formatRecordDate(due)}")
        }
        reminder.dueOdometerKm?.let { add("at ${formatRecordNumber(it)} km") }
    }
    return parts.joinToString(" · ").ifEmpty { "No threshold recorded" }
}
