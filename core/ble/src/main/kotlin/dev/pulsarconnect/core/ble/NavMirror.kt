package dev.pulsarconnect.core.ble

import java.io.ByteArrayOutputStream

/** Navigation cue transitions emitted by an opted-in navigation notification parser. */
sealed interface NavEvent {
    /** An active route cue formatted as compact ASCII (`<= 30` UTF-8 bytes). */
    data class Update(val text: String) : NavEvent

    /** Navigation ended or its ongoing notification was dismissed. */
    data object Ended : NavEvent
}

/**
 * Opt-in configuration governing navigation mirroring over the caller-name slot.
 *
 * The wire call state is deliberately fixed to [CallState.INCOMING_CALL]: it is the only mode
 * live-verified to display cue text on the N150, and it never flips the cluster into a call-duration
 * state. A real call always takes priority in the sender loop.
 */
data class NavMirrorOptions(val enabled: Boolean = false)

/**
 * Formats and caps navigation strings for the 30-byte caller-text area (`bytes[21..50]`).
 *
 * Unlike [CallerName], numeric values (distances like `250m`, highway numbers like `NH48`) are
 * legitimate navigation cues and must not be replaced by a fallback label. Arrows and legacy symbol
 * tokens are normalized to single-letter maneuver codes (`L`, `R`, `S`, `U`, `O`, `A`, `M`, `E`) so
 * the N150's segmented/dot-matrix display never shows blank boxes or unsupported glyphs.
 */
object NavText {
    const val MAX_BYTES: Int = CallerName.MAX_BYTES

    private val WHITESPACE = Regex("""\s+""")

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val normalized = raw
            .replace(">>", "M")
            .replace("/>", "R")
            .replace("\\<", "L")
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            when (ch) {
                '→', '↱', '➡', '➔', '↗', '↘', '>' -> sb.append('R')
                '←', '↰', '⬅', '↖', '↙', '<' -> sb.append('L')
                '↑', '⬆', '^' -> sb.append('S')
                '↺', '↻', '↶', '↷' -> sb.append('U')
                '@' -> sb.append('O')
                '*' -> sb.append('A')
                '·', '•' -> sb.append(' ')
                '–', '—' -> sb.append('-')
                else -> if (ch.code in 0x20..0x7E) sb.append(ch)
            }
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    fun wireText(raw: String?): String =
        wireBytes(raw).toString(Charsets.UTF_8)

    fun wireBytes(raw: String?): ByteArray {
        val sanitized = sanitize(raw)
        if (sanitized.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < sanitized.length) {
            val codePoint = sanitized.codePointAt(index)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > MAX_BYTES) break
            out.write(bytes)
            index += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }
}
