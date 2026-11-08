package dev.pulsarconnect.app.calls

import dev.pulsarconnect.core.ble.CallerName

/**
 * The only fields read from a call notification.
 *
 * Kept as a plain snapshot so the privacy rules can be unit-tested without Android. Message
 * bodies, numbers, contact URIs and every other notification extra are never copied here.
 */
data class CallNotificationSnapshot(
    val packageName: String,
    val category: String?,
    val title: String?,
    val text: String?,
)

/** Privacy-safe result of inspecting a dialer notification. */
sealed interface CallNotificationSignal {
    /** The dialer's display label; null when nothing safe could be read. */
    data class CallerName(val name: String?) : CallNotificationSignal

    /** A missed-call notification. The cluster only needs the fixed icon. */
    data object Missed : CallNotificationSignal
}

/**
 * Maps dialer notifications to call events without reading contacts or call logs.
 *
 * Only notifications from an explicit dialer allowlist are considered. The caller label is
 * rejected when it looks like a phone number or a generic placeholder, so the wire path can only
 * ever see a real display name or the generic "Incoming call" fallback.
 */
class CallNotificationParser(dialerPackages: Set<String>) {
    private val dialers = dialerPackages.map { it.lowercase() }.toSet()

    /** True for the configured dialer packages; lets callers skip reading extras otherwise. */
    fun accepts(packageName: String): Boolean = packageName.lowercase() in dialers

    fun parse(snapshot: CallNotificationSnapshot): CallNotificationSignal? {
        if (!accepts(snapshot.packageName)) return null

        val title = clean(snapshot.title)
        val text = clean(snapshot.text)
        val missed = snapshot.category == CATEGORY_MISSED_CALL ||
            looksMissed(title) ||
            looksMissed(text)
        if (missed) return CallNotificationSignal.Missed

        if (snapshot.category != CATEGORY_CALL) return null
        return CallNotificationSignal.CallerName(extractName(title) ?: extractName(text))
    }

    private fun clean(raw: String?): String? =
        raw?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun looksMissed(value: String?): Boolean {
        val text = value?.lowercase() ?: return false
        return MISSED_MARKERS.any { text.contains(it) }
    }

    private fun extractName(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.length > MAX_NAME_CHARS) return null
        if (value.none { it.isLetter() }) return null
        if (CallerName.isPhoneLike(value)) return null
        if (GENERIC_LABELS.any { value.equals(it, ignoreCase = true) }) return null
        return value
    }

    private companion object {
        // Public Notification category values, kept as literals so this parser stays pure JVM.
        const val CATEGORY_CALL = "call"
        const val CATEGORY_MISSED_CALL = "missed_call"

        const val MAX_NAME_CHARS = 64

        val MISSED_MARKERS = listOf("missed", "unanswered")

        /**
         * Labels that carry no identity. Sending them as a caller name would be noise; the
         * protocol's own fallback is used instead.
         */
        val GENERIC_LABELS = setOf(
            "incoming call",
            "incoming",
            "calling",
            "call",
            "ongoing call",
            "dialing",
            "unknown",
            "unknown caller",
            "private number",
            "restricted",
        )
    }
}
