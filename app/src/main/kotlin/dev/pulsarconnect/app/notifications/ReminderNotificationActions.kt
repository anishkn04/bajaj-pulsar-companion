package dev.pulsarconnect.app.notifications

interface ReminderNotificationActions {
    val enabled: Boolean
    fun setEnabled(enabled: Boolean)
    fun refresh()
    fun onSignedOut()
    fun onReminderCompleted(id: Long)

    /** Clears the delivery ledger so an edited or snoozed threshold can alert again. */
    fun onReminderRearmed(id: Long)

    /** Clears completion and delivery state after the rider undoes "Mark done". */
    fun onReminderReopened(id: Long)

    /** Forgets a deleted reminder so its ID can never suppress a future alert. */
    fun onReminderDeleted(id: Long)
}

object NoOpReminderNotifications : ReminderNotificationActions {
    override val enabled = false
    override fun setEnabled(enabled: Boolean) = Unit
    override fun refresh() = Unit
    override fun onSignedOut() = Unit
    override fun onReminderCompleted(id: Long) = Unit
    override fun onReminderRearmed(id: Long) = Unit
    override fun onReminderReopened(id: Long) = Unit
    override fun onReminderDeleted(id: Long) = Unit
}
