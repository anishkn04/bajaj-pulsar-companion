package dev.pulsarconnect.core.model

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTest {

    private val issuedAt = Instant.parse("2026-11-01T10:00:00Z")

    @Test
    fun `is not expired well before its expiry`() {
        val session = Session("token", issuedAt = issuedAt, expiresAt = issuedAt.plusSeconds(3600))

        assertFalse(session.isExpiredAt(issuedAt.plusSeconds(60)))
    }

    @Test
    fun `is treated as expired inside the safety skew`() {
        val session = Session("token", issuedAt = issuedAt, expiresAt = issuedAt.plusSeconds(3600))

        assertTrue(session.isExpiredAt(issuedAt.plusSeconds(3600 - 10), skew = Duration.ofSeconds(30)))
    }

    @Test
    fun `without an advertised expiry only the server can say it expired`() {
        val session = Session("token", issuedAt = issuedAt, expiresAt = null)

        assertFalse(session.isExpiredAt(issuedAt.plus(Duration.ofDays(365))))
    }
}
