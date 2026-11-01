package dev.pulsarconnect.core.model

/**
 * Everything needed to come back as the same user without asking them again.
 */
data class StoredSession(
    val credentials: Credentials,
    val session: Session,
)
