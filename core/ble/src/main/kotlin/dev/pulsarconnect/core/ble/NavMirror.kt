package dev.pulsarconnect.core.ble

import java.io.ByteArrayOutputStream

/**
 * Call-state wire mode used when repurposing the 30-byte caller-name field of a GENERAL frame
 * for experimental turn-by-turn navigation cues on clusters without native TBT support (e.g. N150).
 */
enum class NavWireMode(val callState: CallState) {
    /** Wire value 3 (`ACTIVE_CALL`), flipping bytes 3/4 (`[3]=1, [4]=0`). */
    ACTIVE_CALL(CallState.ACTIVE_CALL),

    /** Wire value 1 (`INCOMING_CALL`), live-verified to display text on the N150 cluster. */
    INCOMING_CALL(CallState.INCOMING_CALL),
}

/** Navigation cue transitions emitted by an opted-in navigation notification parser. */
sealed interface NavEvent {
    /** An active route cue formatted as compact ASCII (`<= 30` UTF-8 bytes). */
    data class Update(val text: String) : NavEvent

    /** Navigation ended or its ongoing notification was dismissed. */
    data object Ended : NavEvent
}

/** Opt-in configuration governing experimental TBT mirroring over the caller-name slot. */
data class NavMirrorOptions(
    val enabled: Boolean = false,
    val wireMode: NavWireMode = NavWireMode.ACTIVE_CALL,
)

/**
 * Formats and caps navigation strings for the 30-byte caller-text area (`bytes[21..50]`).
 *
 * Unlike [CallerName], numeric values (distances like `250m`, highway numbers like `NH48`) are
 * legitimate navigation cues and must not be replaced by a fallback label. Non-ASCII characters
 * are normalized to safe ASCII tokens so segmented/dot-matrix bike LCDs never show blank boxes.
 */
object NavText {
    const val MAX_BYTES: Int = CallerName.MAX_BYTES

    private val WHITESPACE = Regex("""\s+""")

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '→', '↱', '➡', '➔' -> sb.append('>')
                '←', '↰', '⬅' -> sb.append('<')
                '↑', '⬆' -> sb.append('^')
                '↓', '⬇' -> sb.append('v')
                '↺', '↻', '↶', '↷' -> sb.append('U')
                '↗', '↘' -> sb.append("/>")
                '↖', '↙' -> sb.append("\\<")
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
