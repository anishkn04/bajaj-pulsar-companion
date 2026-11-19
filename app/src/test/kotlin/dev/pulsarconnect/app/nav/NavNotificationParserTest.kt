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
