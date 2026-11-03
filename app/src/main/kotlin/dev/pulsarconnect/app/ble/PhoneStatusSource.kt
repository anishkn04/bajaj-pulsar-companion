package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.PhoneStatus
import kotlinx.coroutines.flow.Flow

/**
 * Privacy-safe phone battery and cellular signal readings.
 *
 * Implementations must never read contacts, call logs, phone numbers, or message content.
 */
fun interface PhoneStatusSource {
    /** Cold flow of the latest readings; collection owns any platform listener. */
    fun statuses(): Flow<PhoneStatus>
}
