package dev.pulsarconnect.core.session

import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the user's credentials and bearer session so that restarting the
 * app never means re-entering anything.
 *
 * Storage is a [SecretStore]; the production implementation encrypts with a
 * key held in the Android Keystore. This class only decides *what* is stored
 * and guarantees that unreadable data degrades to "not signed in" instead of
 * crashing.
 */
class SessionRepository(
    private val secrets: SecretStore,
) {

    fun save(stored: StoredSession) {
        secrets.write(KEY, json.encodeToString(Payload.fromDomain(stored)))
    }

    fun load(): StoredSession? {
        val encoded = secrets.read(KEY) ?: return null
        return runCatching { json.decodeFromString<Payload>(encoded).toDomain() }.getOrNull()
    }

    fun clear() {
        secrets.delete(KEY)
    }

    @Serializable
    private data class Payload(
        val vin: String,
        val accessCode: String,
        val accessToken: String,
        val userToken: String? = null,
        val issuedAt: String,
        val expiresAt: String? = null,
    ) {
        fun toDomain(): StoredSession = StoredSession(
            credentials = Credentials(
                vin = Vin.of(vin),
                accessCode = accessCode,
            ),
            session = Session(
                accessToken = accessToken,
                userToken = userToken,
                issuedAt = Instant.parse(issuedAt),
                expiresAt = expiresAt?.let(Instant::parse),
            ),
        )

        companion object {
            fun fromDomain(stored: StoredSession): Payload = Payload(
                vin = stored.credentials.vin.value,
                accessCode = stored.credentials.accessCode,
                accessToken = stored.session.accessToken,
                userToken = stored.session.userToken,
                issuedAt = stored.session.issuedAt.toString(),
                expiresAt = stored.session.expiresAt?.toString(),
            )
        }
    }

    internal companion object {
        const val KEY = "session.v1"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
