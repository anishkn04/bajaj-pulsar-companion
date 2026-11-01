package dev.pulsarconnect.core.model

import java.time.Instant

/**
 * A bearer session issued by the backend.
 *
 * [expiresAt] is null when the server does not advertise a lifetime; callers
 * must then rely on a 401 to discover expiry.
 */
data class Session(
    val accessToken: String,
    val refreshToken: String? = null,
    val issuedAt: Instant,
    val expiresAt: Instant? = null,
) {
    init {
        require(accessToken.isNotBlank()) { "access token must not be blank" }
    }

    fun isExpiredAt(now: Instant, skew: java.time.Duration = java.time.Duration.ofSeconds(30)): Boolean {
        val expiry = expiresAt ?: return false
        return !now.isBefore(expiry.minus(skew))
    }
}
