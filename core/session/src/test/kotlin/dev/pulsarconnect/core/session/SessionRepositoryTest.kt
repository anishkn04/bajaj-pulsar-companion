package dev.pulsarconnect.core.session

import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionRepositoryTest {

    private val credentials = Credentials(
        vin = Vin.of("MD2ABCDEFGH123456"),
        accessCode = "test-access-code",
    )

    private val session = Session(
        accessToken = "access-token-1",
        userToken = "user-token-1",
        issuedAt = Instant.parse("2026-11-01T10:00:00Z"),
        expiresAt = Instant.parse("2026-11-01T11:00:00Z"),
    )

    @Test
    fun `session survives process death`() {
        val secrets = InMemorySecretStore()

        SessionRepository(secrets).save(StoredSession(credentials, session))

        // A new repository over the same persisted bytes is what the app sees
        // after being killed and relaunched.
        val restored = SessionRepository(secrets).load()

        assertEquals(StoredSession(credentials, session), restored)
    }

    @Test
    fun `load returns null when nothing has been stored`() {
        assertNull(SessionRepository(InMemorySecretStore()).load())
    }

    @Test
    fun `load returns null instead of throwing on corrupt data`() {
        val secrets = InMemorySecretStore(mapOf(SessionRepository.KEY to "{not json"))

        assertNull(SessionRepository(secrets).load())
    }

    @Test
    fun `clear forgets the stored credentials and tokens`() {
        val secrets = InMemorySecretStore()
        val repository = SessionRepository(secrets)
        repository.save(StoredSession(credentials, session))

        repository.clear()

        assertNull(repository.load())
    }
}
