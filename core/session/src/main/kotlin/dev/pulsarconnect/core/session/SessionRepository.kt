package dev.pulsarconnect.core.session

import dev.pulsarconnect.core.model.StoredSession

/**
 * Persists the user's credentials and bearer session so that restarting the
 * app never means re-entering anything.
 */
class SessionRepository(
    private val secrets: SecretStore,
) {

    fun save(stored: StoredSession) {
        TODO("not implemented")
    }

    fun load(): StoredSession? {
        TODO("not implemented")
    }

    fun clear() {
        TODO("not implemented")
    }

    internal companion object {
        const val KEY = "session.v1"
    }
}
