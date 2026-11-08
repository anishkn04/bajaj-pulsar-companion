package dev.pulsarconnect.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallNotificationParserTest {
    private val dialer = "com.android.dialer"
    private val parser = CallNotificationParser(setOf(dialer, "com.android.phone"))

    private fun snapshot(
        packageName: String = dialer,
        category: String? = "call",
        title: String? = null,
        text: String? = null,
    ) = CallNotificationSnapshot(packageName, category, title, text)

    @Test
    fun `notifications from other apps are ignored even when they look like calls`() {
        assertNull(parser.parse(snapshot(packageName = "com.example.chat", title = "Asha")))
    }

    @Test
    fun `package matching ignores case`() {
        assertEquals(
            CallNotificationSignal.CallerName("Asha"),
            parser.parse(snapshot(packageName = "COM.ANDROID.DIALER", title = "Asha")),
        )
    }

    @Test
    fun `accepts only allowlisted dialers so extras are not read elsewhere`() {
        org.junit.Assert.assertTrue(parser.accepts(dialer))
        org.junit.Assert.assertTrue(parser.accepts("Com.Android.Phone"))
        org.junit.Assert.assertFalse(parser.accepts("com.example.chat"))
    }

    @Test
    fun `call notification exposes only the display name`() {
        assertEquals(
            CallNotificationSignal.CallerName("Asha"),
            parser.parse(snapshot(title = "Asha", text = "Incoming call")),
        )
    }

    @Test
    fun `text is used when the title carries no name`() {
        assertEquals(
            CallNotificationSignal.CallerName("Asha"),
            parser.parse(snapshot(title = "Incoming call", text = "Asha")),
        )
    }

    @Test
    fun `whitespace in the label is collapsed`() {
        assertEquals(
            CallNotificationSignal.CallerName("Asha Rai"),
            parser.parse(snapshot(title = "  Asha \n Rai  ")),
        )
    }

    @Test
    fun `phone numbers are rejected rather than forwarded`() {
        for (number in listOf("+1 (555) 010-9999", "555-0100", "5550100")) {
            assertEquals(
                CallNotificationSignal.CallerName(null),
                parser.parse(snapshot(title = number)),
            )
        }
    }

    @Test
    fun `generic placeholders are rejected so the protocol fallback is used`() {
        for (label in listOf("Incoming call", "Unknown", "Private number", "calling")) {
            assertEquals(
                CallNotificationSignal.CallerName(null),
                parser.parse(snapshot(title = label)),
            )
        }
    }

    @Test
    fun `names mixing digits and letters are kept`() {
        assertEquals(
            CallNotificationSignal.CallerName("Taxi 555"),
            parser.parse(snapshot(title = "Taxi 555")),
        )
    }

    @Test
    fun `an overlong label is dropped instead of truncating personal data`() {
        val label = "A".repeat(65)
        assertEquals(
            CallNotificationSignal.CallerName(null),
            parser.parse(snapshot(title = label)),
        )
    }

    @Test
    fun `non-call categories are ignored`() {
        assertNull(parser.parse(snapshot(category = "msg", title = "Asha")))
        assertNull(parser.parse(snapshot(category = null, title = "Asha")))
    }

    @Test
    fun `missed-call category maps to the fixed icon signal`() {
        assertEquals(
            CallNotificationSignal.Missed,
            parser.parse(snapshot(category = "missed_call", title = "Asha")),
        )
    }

    @Test
    fun `missed text is detected without the category`() {
        assertEquals(
            CallNotificationSignal.Missed,
            parser.parse(snapshot(category = "call", title = "Missed call")),
        )
        assertEquals(
            CallNotificationSignal.Missed,
            parser.parse(snapshot(category = null, text = "1 unanswered call")),
        )
    }

    @Test
    fun `a missed notification never exposes a caller name`() {
        val signal = parser.parse(
            snapshot(category = "missed_call", title = "Asha", text = "Missed call"),
        )
        assertEquals(CallNotificationSignal.Missed, signal)
    }
}
