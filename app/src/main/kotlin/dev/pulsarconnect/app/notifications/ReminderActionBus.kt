package dev.pulsarconnect.app.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local signal that a reminder changed outside the UI (a notification action).
 * Screens reload on it so the shade cannot leave stale rows behind.
 */
class ReminderActionBus {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
