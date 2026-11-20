package dev.pulsarconnect.app.nav

import dev.pulsarconnect.core.ble.NavCue
import dev.pulsarconnect.core.ble.NavManeuver
import kotlin.math.roundToInt

/**
 * The only fields inspected from an active navigation notification.
 *
 * Kept as a plain snapshot so parsing can be unit-tested on the JVM without Android framework
 * dependencies. Route history is never stored or logged.
 */
data class NavNotificationSnapshot(
    val packageName: String,
    val category: String?,
    val isOngoing: Boolean,
    val title: String?,
    val text: String?,
    val subText: String?,
)

/**
 * Extracts the fields the cluster's navigation frame needs from Google Maps and Organic Maps
 * ongoing notifications. Explicit direction words in the notification text win; otherwise the
 * cue falls back to STRAIGHT. No notification content is persisted or logged.
 */
class NavNotificationParser(
    packages: Set<String> = DEFAULT_PACKAGES,
) {
    private val allowedPackages = packages.map { it.lowercase() }.toSet()

    fun accepts(packageName: String): Boolean = packageName.lowercase() in allowedPackages

    fun parse(snapshot: NavNotificationSnapshot): NavCue? {
        if (!accepts(snapshot.packageName)) return null
        if (!snapshot.isOngoing && snapshot.category != CATEGORY_NAVIGATION) return null

        val title = clean(snapshot.title).orEmpty()
        val text = clean(snapshot.text).orEmpty()
        val subText = clean(snapshot.subText).orEmpty()
        val instruction = listOf(title, text).filter { it.isNotEmpty() }.joinToString(" ").trim()
        if (instruction.isEmpty()) return null
        if (NON_TURN_MARKERS.any { instruction.equals(it, ignoreCase = true) }) return null

        val maneuver = detectManeuver(instruction) ?: NavManeuver.STRAIGHT
        return NavCue(
            maneuver = maneuver,
            distanceMeters = parseDistanceMeters(title) ?: parseDistanceMeters(text),
            totalDistanceMeters = parseDistanceMeters(subText),
            etaHour = parseEtaClock(subText)?.hour,
            etaMinute = parseEtaClock(subText)?.minute,
            etaPm = parseEtaClock(subText)?.pm ?: false,
            roundaboutExit = parseRoundaboutExit(instruction),
            text = abbreviateRoad(extractStreet(text, title)),
        )
    }

    private fun clean(raw: String?): String? =
        raw?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseDistanceMeters(raw: String?): Int? {
        val match = raw?.let { DISTANCE_REGEX.find(it) } ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val metres = when (match.groupValues[2].lowercase()) {
            "km", "kilometer", "kilometers" -> value * 1_000.0
            "mi", "mile", "miles" -> value * 1_609.34
            "ft", "foot", "feet" -> value * 0.3048
            "yd", "yard", "yards" -> value * 0.9144
            else -> value
        }
        return metres.roundToInt().coerceAtLeast(0)
    }

    private data class Eta(val hour: Int, val minute: Int, val pm: Boolean)

    private fun parseEtaClock(raw: String): Eta? {
        val match = CLOCK_REGEX.find(raw) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val marker = match.groupValues[3].lowercase()
        val pm = when {
            marker == "pm" -> true
            marker == "am" -> false
            hour > 12 -> {
                hour -= 12
                true
            }
            hour == 0 -> {
                hour = 12
                false
            }
            else -> false
        }
        return Eta(hour.coerceIn(1..12), minute, pm)
    }

    private fun detectManeuver(instruction: String): NavManeuver? {
        val lower = instruction.lowercase()
        if (containsAny(lower, "reached your destination", "you have reached", "you've reached",
                "you have arrived", "arrived at your destination")
        ) {
            return NavManeuver.DESTINATION
        }
        val hasDirection = containsAny(
            lower, "turn left", "turn right", "left onto", "right onto", "left on", "right on",
            "keep left", "keep right", "slight", "sharp", "roundabout", "rotary", "traffic circle",
            "fork", "merge", "u-turn", "u turn", "uturn", "take the", "exit", "continue",
            "straight", "head ", "go ",
        )
        if (!hasDirection && lower.contains("arrive")) return NavManeuver.DESTINATION
        return when {
            containsAny(lower, "roundabout", "rotary", "traffic circle") ->
                if (lower.contains("right")) NavManeuver.ROUNDABOUT_RIGHT else NavManeuver.ROUNDABOUT_LEFT
            containsAny(lower, "u-turn", "u turn", "uturn") ->
                if (lower.contains("right")) NavManeuver.U_TURN_RIGHT else NavManeuver.U_TURN_LEFT
            lower.contains("sharp left") -> NavManeuver.SHARP_LEFT
            lower.contains("sharp right") -> NavManeuver.SHARP_RIGHT
            containsAny(lower, "slight left", "bear left") -> NavManeuver.SLIGHT_LEFT
            containsAny(lower, "slight right", "bear right") -> NavManeuver.SLIGHT_RIGHT
            lower.contains("keep left") -> NavManeuver.KEEP_LEFT
            lower.contains("keep right") -> NavManeuver.KEEP_RIGHT
            lower.contains("merge") -> NavManeuver.MERGE
            lower.contains("fork left") -> NavManeuver.FORK_LEFT
            lower.contains("fork right") -> NavManeuver.FORK_RIGHT
            containsAny(lower, "take the", "exit") -> when {
                lower.contains("left") -> NavManeuver.FORK_LEFT
                lower.contains("right") -> NavManeuver.FORK_RIGHT
                else -> null
            }
            containsAny(lower, "turn left", "left onto", "left on") -> NavManeuver.TURN_LEFT
            containsAny(lower, "turn right", "right onto", "right on") -> NavManeuver.TURN_RIGHT
            containsAny(lower, "continue", "straight", "head ", "go ") -> NavManeuver.STRAIGHT
            else -> null
        }
    }

    private fun parseRoundaboutExit(instruction: String): Int {
        val lower = instruction.lowercase()
        REGULAR_EXIT_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        EXIT_NUMBER_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    /** Reduces Maps' instruction text to just the road name. */
    private fun extractStreet(text: String, title: String): String {
        var street = text.ifEmpty { title }
        val lower = street.lowercase()
        val towards = lower.indexOf(" towards ")
        val onto = lower.indexOf(" onto ")
        when {
            towards >= 0 -> street = street.substring(towards + 9)
            onto >= 0 -> street = street.substring(onto + 6)
            lower.startsWith("towards ") -> street = street.substring(8)
            lower.startsWith("onto ") -> street = street.substring(5)
        }
        street = street.trim()
        val trimmed = street.lowercase()
        if (INSTRUCTION_PREFIXES.any { trimmed.startsWith(it) }) return ""
        return street
    }

    private fun abbreviateRoad(raw: String): String {
        var out = raw
        for ((pattern, replacement) in ROAD_ABBREVIATIONS) {
            out = out.replace(pattern, replacement)
        }
        return out.replace(WHITESPACE, " ").trim()
    }

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any { value.contains(it) }

    companion object {
        const val CATEGORY_NAVIGATION: String = "navigation"

        val DEFAULT_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.maps",
            "app.organicmaps",
            "app.organicmaps.debug",
            "app.organicmaps.fdroid",
        )

        private val WHITESPACE = Regex("""\s+""")
        private val DISTANCE_REGEX = Regex(
            """\b(\d+(?:[.,]\d+)?)\s*(km|kilometers?|m|meters?|mi|miles?|ft|feet|foot|yd|yards?)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val CLOCK_REGEX = Regex("""(\d{1,2}):(\d{2})(?:\s*([ap]m))?""", RegexOption.IGNORE_CASE)
        private val REGULAR_EXIT_REGEX = Regex("""(\d+)(?:st|nd|rd|th)\s+exit""", RegexOption.IGNORE_CASE)
        private val EXIT_NUMBER_REGEX = Regex("""exit\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val NON_TURN_MARKERS = setOf(
            "navigation",
            "navigating",
            "gps",
            "searching for gps",
        )
        private val INSTRUCTION_PREFIXES = listOf(
            "turn ", "keep ", "merge", "continue", "head ", "take ", "exit", "slight", "sharp",
            "u-turn", "u turn", "roundabout", "arrive", "start", "rerout",
        )
        private val ROAD_ABBREVIATIONS: List<Pair<Regex, String>> = listOf(
            Regex("""\bRoad\b""", RegexOption.IGNORE_CASE) to "Rd",
            Regex("""\bStreet\b""", RegexOption.IGNORE_CASE) to "St",
            Regex("""\bAvenue\b""", RegexOption.IGNORE_CASE) to "Ave",
            Regex("""\bBoulevard\b""", RegexOption.IGNORE_CASE) to "Blvd",
            Regex("""\bHighway\b""", RegexOption.IGNORE_CASE) to "Hwy",
            Regex("""\bExpressway\b""", RegexOption.IGNORE_CASE) to "Expy",
            Regex("""\bDrive\b""", RegexOption.IGNORE_CASE) to "Dr",
            Regex("""\bLane\b""", RegexOption.IGNORE_CASE) to "Ln",
            Regex("""\bCircle\b""", RegexOption.IGNORE_CASE) to "Cir",
            Regex("""\bSquare\b""", RegexOption.IGNORE_CASE) to "Sq",
            Regex("""\bTowards\b""", RegexOption.IGNORE_CASE) to "to",
            Regex("""\bToward\b""", RegexOption.IGNORE_CASE) to "to",
        )
    }
}
