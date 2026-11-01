package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.ServiceReminder

/** A reminder is due at whichever configured threshold is reached first. */
fun ServiceReminder.isDue(currentOdometer: Double, currentTimeMillis: Long): Boolean =
    !completed && (
        dueDateMillis?.let { it <= currentTimeMillis } == true ||
            dueOdometerKm?.let { it <= currentOdometer } == true
    )
