package dev.pulsarconnect.app.nav

import dev.pulsarconnect.core.ble.NavManeuver
import dev.pulsarconnect.core.ble.NavText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavNotificationParserTest {

    private val parser = NavNotificationParser()

    @Test
    fun `parses google maps turn cue into structured fields`() {
        val cue = parser.parse(
            NavNotificationSnapshot(
                packageName = "com.google.android.apps.maps",
                category = "navigation",
                isOngoing = true,
                title = "250 m",
                text = "Turn right onto Mahatma Gandhi Road",
                subText = "6 hr 17 min · 3.2 km · 4:57 am ETA",
            ),
        )

        assertNotNull(cue)
        cue!!
        assertEquals(NavManeuver.TURN_RIGHT, cue.maneuver)
        assertEquals(250, cue.distanceMeters)
        assertEquals(3_200, cue.totalDistanceMeters)
        assertEquals(4, cue.etaHour)
        assertEquals(57, cue.etaMinute)
        assertFalse(cue.etaPm)
        assertEquals("Mahatma Gandhi Rd", cue.text)
        assertFallbackWithinLimit(cue)
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

        assertNotNull(cue)
        cue!!
        assertEquals(NavManeuver.SLIGHT_LEFT, cue.maneuver)
        assertEquals(1_200, cue.distanceMeters)
        assertEquals("Old Airport Hwy", cue.text)
        assertFallbackWithinLimit(cue)
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

        assertNotNull(cue)
        cue!!
        assertEquals(NavManeuver.STRAIGHT, cue.maneuver)
        assertEquals(100, cue.distanceMeters)
        assertEquals("Maitighar Rd", cue.text)
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

    private fun assertFallbackWithinLimit(cue: dev.pulsarconnect.core.ble.NavCue) {
        val fallback = NavText.fallbackText(cue)
        org.junit.Assert.assertTrue(fallback.isNotEmpty())
        org.junit.Assert.assertTrue(fallback.toByteArray(Charsets.UTF_8).size <= NavText.MAX_BYTES)
    }
}
