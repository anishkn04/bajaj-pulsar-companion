package dev.pulsarconnect.app.nav

import dev.pulsarconnect.core.ble.NavText

/**
 * The only fields inspected from an active navigation notification.
 *
 * Kept as a plain snapshot so parsing and ASCII condensation can be unit-tested on the JVM
 * without Android framework dependencies. Route history is never stored or logged.
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
 * Condenses ongoing turn-by-turn notifications from Google Maps and Organic Maps into compact
 * ASCII strings (`<= 30` UTF-8 bytes) suitable for the Pulsar N150's 30-byte caller-text BLE slot.
 */
class NavNotificationParser(
    packages: Set<String> = DEFAULT_PACKAGES,
) {
    private val allowedPackages = packages.map { it.lowercase() }.toSet()

    fun accepts(packageName: String): Boolean = packageName.lowercase() in allowedPackages

    fun parse(snapshot: NavNotificationSnapshot): String? {
        if (!accepts(snapshot.packageName)) return null
        if (!snapshot.isOngoing && snapshot.category != CATEGORY_NAVIGATION) return null

        val title = clean(snapshot.title)
        val text = clean(snapshot.text)
        val combined = listOfNotNull(title, text).joinToString(" ").trim()
        if (combined.isEmpty()) return null
        if (NON_TURN_MARKERS.any { combined.equals(it, ignoreCase = true) }) return null

        val arrow = detectManeuverToken(combined)
        val distance = extractDistance(title) ?: extractDistance(text)
        val target = extractTarget(title, text)

        val parts = buildList {
            if (arrow != null) {
                add(arrow)
                if (distance != null) add(distance)
            } else {
                if (distance != null) add(distance)
                if (!target.isNullOrEmpty()) add(abbreviateRoad(target))
            }
        }
        val rawCue = if (parts.isNotEmpty()) {
            parts.joinToString(" ")
        } else {
            abbreviateRoad(combined)
        }
        return NavText.wireText(rawCue).ifEmpty { null }
    }

    private fun clean(raw: String?): String? =
        raw?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun extractDistance(raw: String?): String? {
        val value = raw ?: return null
        val match = DISTANCE_REGEX.find(value) ?: return null
        val number = match.groupValues[1]
        val unit = match.groupValues[2].lowercase()
        val compactUnit = when (unit) {
            "meters", "meter", "m" -> "m"
            "kilometers", "kilometer", "km" -> "km"
            "feet", "foot", "ft" -> "ft"
            "yards", "yard", "yd" -> "yd"
            "miles", "mile", "mi" -> "mi"
            else -> unit
        }
        return "$number$compactUnit"
    }

    private fun detectManeuverToken(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("u-turn") || lower.contains("uturn") -> "U"
            lower.contains("roundabout") || lower.contains("rotary") -> "@"
            lower.contains("arrive") || lower.contains("destination") -> "*"
            lower.contains("slight right") || lower.contains("keep right") || lower.contains("bear right") -> "/>"
            lower.contains("slight left") || lower.contains("keep left") || lower.contains("bear left") -> "\\<"
            lower.contains("sharp right") || lower.contains("turn right") || lower.contains("right onto") || lower.contains("right") -> ">"
            lower.contains("sharp left") || lower.contains("turn left") || lower.contains("left onto") || lower.contains("left") -> "<"
            lower.contains("merge") || lower.contains("exit") -> ">>"
            lower.contains("straight") || lower.contains("continue") || lower.contains("head ") -> "^"
            else -> null
        }
    }

    private fun extractTarget(title: String?, text: String?): String? {
        val candidates = listOfNotNull(text, title)
        for (candidate in candidates) {
            // Skip strings that are purely a distance (e.g. "250 m" in Google Maps title).
            if (PURE_DISTANCE_REGEX.matches(candidate.trim())) continue
            var stripped = candidate
                .replace(DISTANCE_PREFIX_REGEX, "")
                .replace(LEADING_PHRASE_REGEX, "")
                .trim(' ', '-', '·', ':')
            if (stripped.isNotEmpty()) {
                return stripped
            }
        }
        return null
    }

    private fun abbreviateRoad(raw: String): String {
        var out = raw
        for ((pattern, replacement) in ROAD_ABBREVIATIONS) {
            out = out.replace(pattern, replacement)
        }
        return out.replace(WHITESPACE, " ").trim()
    }

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
        private val PURE_DISTANCE_REGEX = Regex(
            """^\s*(?:in\s+)?\d+(?:[.,]\d+)?\s*(?:km|kilometers?|m|meters?|mi|miles?|ft|feet|foot|yd|yards?)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val DISTANCE_PREFIX_REGEX = Regex(
            """^(?:in\s+)?\d+(?:[.,]\d+)?\s*(?:km|kilometers?|m|meters?|mi|miles?|ft|feet|foot|yd|yards?)\s*(?:[-·,]\s*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val LEADING_PHRASE_REGEX = Regex(
            """^(?:make\s+a\s+u-?turn|turn\s+(?:sharp\s+|slight\s+)?(?:left|right)|(?:keep|bear|slight)\s+(?:left|right)|continue\s+straight|continue|head\s+(?:north|south|east|west|northeast|northwest|southeast|southwest)|take\s+(?:the\s+)?exit(?:\s+toward)?|at\s+the\s+roundabout(?:\s+take)?)\s*(?:onto|on|toward|towards|to)?\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_TURN_MARKERS = setOf(
            "navigation",
            "navigating",
            "gps",
            "searching for gps",
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
