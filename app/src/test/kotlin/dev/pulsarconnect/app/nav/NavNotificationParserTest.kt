package dev.pulsarconnect.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavNotificationParserTest {

    private val parser = NavNotificationParser()

    @Test
    fun `parses google maps turn cue into compact ASCII under 30 bytes`() {
        val cue = parser.parse(
            NavNotificationSnapshot(
                packageName = "com.google.android.apps.maps",
                category = "navigation",
                isOngoing = true,
                title = "250 m",
                text = "Turn right onto Mahatma Gandhi Road",
                subText = "12 min",
            ),
        )
        assertEquals("> 250m Mahatma Gandhi Rd", cue)
        assertTrue(cue!!.toByteArray(Charsets.UTF_8).size <= 30)
    }

    @Test
    fun `parses organic maps ongoing notification`() {
        val cue = parser.parse(
            NavNotificationSnapshot(
                packageName = "app.organicmaps",
                category = null,
                isOngoing = true,
                title = "1.2 km",
                text = "Slight left onto Old Airport Highway",
                subText = null,
            ),
        )
        assertEquals("\\< 1.2km Old Airport Hwy", cue)
        assertTrue(cue!!.toByteArray(Charsets.UTF_8).size <= 30)
    }

    @Test
    fun `keeps road name when notification has no recognizable maneuver`() {
        val cue = parser.parse(
            NavNotificationSnapshot(
                packageName = "com.google.android.apps.maps",
                category = "navigation",
                isOngoing = true,
                title = "100 m",
                text = "Maitighar Road",
                subText = null,
            ),
        )
        assertEquals("100m Maitighar Rd", cue)
    }

    @Test
    fun `parses current Maps head toward and stay on titles`() {
        listOf(
            "Head toward Sangam Tole Marg" to "^ Sangam Tole Marg",
            "Head west on Srijanshil Marga" to "^ Srijanshil Marga",
            "Turn left to stay on Srijanshil Marga" to "< Srijanshil Marga",
            "90 m · Turn right" to "> 90m",
        ).forEach { (title, expected) ->
            assertEquals(
                expected,
                parser.parse(
                    NavNotificationSnapshot(
                        packageName = "com.google.android.apps.maps",
                        category = "navigation",
                        isOngoing = true,
                        title = title,
                        text = null,
                        subText = "Arrive 6:37 pm",
                    ),
                ),
            )
        }
    }

    @Test
    fun `truncates long road names at a word boundary within 30 bytes`() {
        val cue = parser.parse(
            NavNotificationSnapshot(
                packageName = "com.google.android.apps.maps",
                category = "navigation",
                isOngoing = true,
                title = "250 m",
                text = "Turn right onto Mahatma Gandhi Memorial International Boulevard Extension",
                subText = null,
            ),
        )
        assertEquals("> 250m Mahatma Gandhi Memorial", cue)
        assertTrue(cue!!.toByteArray(Charsets.UTF_8).size <= 30)
    }

    @Test
    fun `ignores transient navigation status updates`() {
        listOf(
            "Starting navigation…",
            "Rerouting…",
            "Calculating route…",
            "Searching for GPS",
            "GPS signal lost",
        ).forEach { title ->
            assertNull(
                parser.parse(
                    NavNotificationSnapshot(
                        packageName = "com.google.android.apps.maps",
                        category = "navigation",
                        isOngoing = true,
                        title = title,
                        text = null,
                        subText = null,
                    ),
                ),
            )
        }
    }

    @Test
    fun `ignores non-ongoing promo notifications and unrelated apps`() {
        assertNull(
            parser.parse(
                NavNotificationSnapshot(
                    packageName = "com.google.android.apps.maps",
                    category = "recommendation",
                    isOngoing = false,
                    title = "Rate your visit",
                    text = "Share a photo",
                    subText = null,
                ),
            ),
        )
        assertNull(
            parser.parse(
                NavNotificationSnapshot(
                    packageName = "com.whatsapp",
                    category = "navigation",
                    isOngoing = true,
                    title = "250 m",
                    text = "Turn right",
                    subText = null,
                ),
            ),
        )
    }
}
