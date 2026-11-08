package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.CallEvent
import kotlinx.coroutines.flow.Flow

/**
 * Privacy-safe phone call transitions for the bike link.
 *
 * Implementations must never read contacts, call logs, phone numbers or message content. The
 * only caller data that may leave this layer is a dialer display name, and the core encoder
 * replaces any phone-shaped value with a generic label.
 */
fun interface PhoneCallSource {
    /** Cold flow of call transitions; collection owns any platform listener. */
    fun events(): Flow<CallEvent>
}
