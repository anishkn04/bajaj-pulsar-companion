package dev.pulsarconnect.app.notifications

interface ReminderNotificationActions {
    val enabled: Boolean
    fun setEnabled(enabled: Boolean)
    fun refresh()
    fun onSignedOut()
    fun onReminderCompleted(id: Long)
}

object NoOpReminderNotifications : ReminderNotificationActions {
    override val enabled = false
    override fun setEnabled(enabled: Boolean) = Unit
    override fun refresh() = Unit
    override fun onSignedOut() = Unit
    override fun onReminderCompleted(id: Long) = Unit
}
