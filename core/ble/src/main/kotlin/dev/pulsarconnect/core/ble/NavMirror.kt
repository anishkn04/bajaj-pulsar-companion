package dev.pulsarconnect.core.ble

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Maneuver glyphs understood by the cluster's dedicated navigation display.
 *
 * The numeric ids are the native icon ids used by the bike's TBT frame, not Android resource ids.
 * The ASCII token is only used by the legacy text fallback; it is never written to a call field
 * when a navigation display is available.
 */
enum class NavManeuver(val iconId: Int, val arrow: String) {
    WRONG_WAY(66, "X"),
    SLIGHT_LEFT(67, "/<"),
    SLIGHT_RIGHT(68, "/>"),
    SHARP_LEFT(69, "<<"),
    SHARP_RIGHT(70, ">>"),
    STRAIGHT(71, "^"),
    DESTINATION(72, "*"),
    TURN_LEFT(73, "<"),
    TURN_RIGHT(74, ">"),
    RAMP_LEFT(75, "<"),
    RAMP_RIGHT(76, ">"),
    ROUNDABOUT_RIGHT(78, "@"),
    U_TURN_LEFT(79, "U"),
    U_TURN_RIGHT(80, "U"),
    FORK_LEFT(81, "<"),
    FORK_RIGHT(82, ">"),
    FERRY_TRAIN(84, "^"),
    ROUNDABOUT_LEFT(85, "@"),
    MERGE(86, ">>"),
    KEEP_RIGHT(88, ">"),
    FERRY(89, "^"),
    KEEP_LEFT(90, "<"),
}

/** One structured navigation cue, independent of how it is carried on the wire. */
data class NavCue(
    val maneuver: NavManeuver = NavManeuver.STRAIGHT,
    val distanceMeters: Int? = null,
    val totalDistanceMeters: Int? = null,
    val etaHour: Int? = null,
    val etaMinute: Int? = null,
    val etaPm: Boolean = false,
    val roundaboutExit: Int = 0,
    val text: String = "",
)

/** Transport selected from the bike's advertised GATT capabilities. */
enum class NavTransport {
    /** Dedicated 48-byte navigation frame on the TBT characteristic. */
    TBT,

    /** 44-byte SMS alert frame; carries compact text but no maneuver glyph. */
    ALERT,

    /** Legacy last resort: compact text in the incoming-call slot of the GENERAL frame. */
    CALL_TEXT,
}

/** Resolves a transport without any rider-facing mode selector. */
object NavTransportResolver {
    fun select(services: List<BleService>): NavTransport? = when {
        TbtProtocol.isSupportedBy(services) -> NavTransport.TBT
        AlertProtocol.isSupportedBy(services) -> NavTransport.ALERT
        PhoneStatusProtocol.isSupportedBy(services) -> NavTransport.CALL_TEXT
        else -> null
    }
}

/** Navigation cue transitions emitted by the opted-in navigation notification parser. */
sealed interface NavEvent {
    data class Update(val cue: NavCue) : NavEvent

    /** Navigation ended or its ongoing notification was dismissed. */
    data object Ended : NavEvent
}

/** Opt-in configuration governing navigation mirroring. */
data class NavMirrorOptions(val enabled: Boolean = false)

/**
 * ASCII condensation for the legacy text transports.
 *
 * Numeric values (distances like `250m`, highway numbers like `NH48`) are legitimate cues and
 * must not be replaced by a fallback label. Non-ASCII characters are normalized to safe tokens so
 * segmented/dot-matrix bike LCDs never show blank boxes.
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

    fun wireText(raw: String?): String = wireBytes(raw).toString(Charsets.UTF_8)

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

    /** Compact arrow-plus-distance text used only when no navigation display is available. */
    fun fallbackText(cue: NavCue): String {
        val parts = buildList {
            add(cue.maneuver.arrow)
            cue.distanceMeters?.let { add(compactDistance(it)) }
            sanitize(cue.text).takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        return wireText(parts.joinToString(" "))
    }

    private fun compactDistance(meters: Int): String = when {
        meters < 1_000 -> "${meters}m"
        else -> {
            val kilometres = meters / 1_000.0
            val rounded = (kilometres * 10).roundToInt() / 10.0
            "${rounded}km"
        }
    }
}
