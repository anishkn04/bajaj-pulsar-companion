package dev.pulsarconnect.core.model

import java.time.Instant

/**
 * Tokens issued by the backend.
 *
 * [accessToken] is the app token (`zersysWhoAmI`) that authorises every
 * request. [userToken] is issued by `/api/ib/rc/login` and is kept because the
 * official client stores it too, though no API call in the current app uses
 * it as a bearer.
 *
 * [expiresAt] is null when the server does not advertise a lifetime; callers
 * must then rely on a 401 to discover expiry. The official client never
 * checks token age at all.
 */
data class Session(
    val accessToken: String,
    val userToken: String? = null,
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
