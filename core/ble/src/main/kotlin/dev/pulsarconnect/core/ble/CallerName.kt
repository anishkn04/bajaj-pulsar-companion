package dev.pulsarconnect.core.ble

import java.io.ByteArrayOutputStream

/**
 * Privacy-safe caller text for the GENERAL frame.
 *
 * The native Android encoder removes symbol/format characters from the contact name and copies
 * at most 30 UTF-8 bytes. When no contact name is known it falls back to the phone number; this
 * app must never do that, so a missing or phone-shaped value becomes [UNKNOWN_LABEL] instead.
 */
object CallerName {
    /** Native Android caps the caller text at 30 UTF-8 bytes. */
    const val MAX_BYTES: Int = 30

    /** Sent instead of a number when the caller has no usable display name. */
    const val UNKNOWN_LABEL: String = "Incoming call"

    /** Same characters the native Android encoder removes before UTF-8 encoding. */
    private val UNWANTED = Regex("""[\p{So}\p{Cn}\-+^]""")

    private const val MIN_PHONE_DIGITS = 5

    /** Removes the characters the native encoder strips, then trims surrounding whitespace. */
    fun sanitize(raw: String?): String =
        raw?.replace(UNWANTED, "")?.trim().orEmpty()

    /**
     * True when [raw] contains no letters and enough digits to plausibly be a phone number.
     * Caller text matching this must never be transmitted.
     */
    fun isPhoneLike(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isLetter() }) return false
        return value.count { it.isDigit() } >= MIN_PHONE_DIGITS
    }

    /**
     * The caller text that may go on the wire: sanitized display name, or [UNKNOWN_LABEL] when
     * the name is empty or looks like a phone number.
     */
    fun wireText(raw: String?): String {
        val sanitized = sanitize(raw)
        return if (sanitized.isBlank() || isPhoneLike(sanitized)) UNKNOWN_LABEL else sanitized
    }

    /**
     * UTF-8 bytes for [wireText], capped at [MAX_BYTES]. Truncation happens on code-point
     * boundaries, unlike the native raw-byte copy, so the receiver never gets invalid UTF-8.
     */
    fun wireBytes(raw: String?): ByteArray = truncate(wireText(raw))

    internal fun truncate(value: String, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes > 0) { "Maximum byte count must be positive" }
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > maxBytes) break
            out.write(bytes)
            index += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }
}
