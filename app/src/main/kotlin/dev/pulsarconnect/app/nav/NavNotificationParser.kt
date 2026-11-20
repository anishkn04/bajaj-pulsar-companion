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
 *
 * Cues are ordered `ARROW DISTANCE ROAD`: the maneuver glyph and step distance are always kept,
 * and a road name is appended only while whole words still fit. Total route distance and ETA are
 * deliberately ignored — the cluster has no room for them and they are not step instructions.
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
        if (TRANSIENT_MARKERS.any { combined.contains(it, ignoreCase = true) }) return null

        val arrow = detectManeuverToken(combined)
        val distance = extractDistance(title) ?: extractDistance(text)
        val road = extractRoad(title, text)

        val parts = buildList {
            arrow?.let(::add)
            distance?.let(::add)
        }
        return fitCue(parts, road).takeIf { it.isNotEmpty() }
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

    /** Reduces a Google Maps instruction to the road/street name, or null when there is none. */
    private fun extractRoad(title: String?, text: String?): String? {
        for (candidate in listOfNotNull(text, title)) {
            if (PURE_DISTANCE_REGEX.matches(candidate)) continue
            val stripped = DISTANCE_PREFIX_REGEX.replace(candidate, "")
                .let { LEADING_PHRASE_REGEX.replace(it, "") }
                .trim(' ', '-', '·', ':', ',')
            if (stripped.isEmpty() || INSTRUCTION_ONLY.matches(stripped.lowercase())) continue
            return abbreviateRoad(stripped)
        }
        return null
    }

    /**
     * Keeps the arrow and distance intact, then adds only whole road words that fit in the
     * 30-byte caller slot. A long road name is truncated at a word boundary rather than mid-word.
     */
    private fun fitCue(priority: List<String>, road: String?): String {
        val prefix = priority.map(::sanitize).filter { it.isNotEmpty() }.joinToString(" ")
        if (road.isNullOrEmpty()) return prefix
        var result = prefix
        for (word in sanitize(road).split(WHITESPACE)) {
            if (word.isEmpty()) continue
            val candidate = if (result.isEmpty()) word else "$result $word"
            // wireBytes() truncates, so measure the raw sanitized string instead.
            if (candidate.toByteArray(Charsets.UTF_8).size > NavText.MAX_BYTES) break
            result = candidate
        }
        return NavText.wireText(result)
    }

    private fun sanitize(raw: String): String = NavText.sanitize(raw)

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
            """^(?:make\s+a\s+u-?turn|turn\s+(?:sharp\s+|slight\s+)?(?:left|right)|(?:keep|bear|slight)\s+(?:left|right)|continue(?:\s+straight)?|head\s+(?:(?:north|south|east|west|northeast|northwest|southeast|southwest)|towards?|to)|take\s+(?:the\s+)?exit(?:\s+toward)?|at\s+the\s+roundabout(?:\s+take)?)\s*(?:to\s+stay\s+on|stay\s+on|onto|on|toward|towards|to)?\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_TURN_MARKERS = setOf(
            "navigation",
            "navigating",
            "gps",
            "searching for gps",
        )
        private val TRANSIENT_MARKERS = listOf(
            "starting navigation",
            "rerout",
            "recalculating",
            "calculating route",
            "searching for gps",
            "gps signal lost",
        )
        private val INSTRUCTION_ONLY = Regex(
            """(?:turn\s+)?(?:sharp\s+|slight\s+)?(?:left|right|straight)|stay\s+on|head(?:\s+\w+)?|continue(?:\s+straight)?|(?:keep|bear)\s+(?:left|right)|arriv\w*|destination""",
            RegexOption.IGNORE_CASE,
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
        )
    }
}
