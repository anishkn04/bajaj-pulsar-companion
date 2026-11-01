package dev.pulsarconnect.core.network

import dev.pulsarconnect.core.model.Credentials
import kotlinx.serialization.json.JsonElement

/**
 * Thin, typed wrapper over the endpoints recovered from the official client.
 *
 * Every method here is a single request; retries and token refresh live in
 * [BajajSessionManager] so the transport stays easy to test.
 */
class BajajApi(
    @Suppress("UNUSED_PARAMETER") private val baseUrl: String = BASE_URL,
) {

    /** `GET /api/whoami` — returns the app token used as the bearer everywhere. */
    suspend fun whoAmI(): String = TODO("not implemented")

    /** `POST /api/ib/rc/login` — checks vehicle ownership with the dealer code. */
    suspend fun logIn(credentials: Credentials, appToken: String): LoginOutcome =
        TODO("not implemented")

    /** `POST /api/ib/rc/validate` — the official app's cold-start gate. */
    suspend fun validate(credentials: Credentials, appToken: String): Boolean =
        TODO("not implemented")

    companion object {
        /** `string/ZERSYS_URL` in the official APK. */
        const val BASE_URL = "https://fms.bajajauto.com"
    }
}

sealed interface LoginOutcome {
    data class Success(
        val userToken: String?,
        val userDetails: JsonElement?,
    ) : LoginOutcome

    data object VehicleNotRegistered : LoginOutcome

    /** Any other server message, e.g. an access code the dealer revoked. */
    data class Rejected(val message: String) : LoginOutcome
}

open class BajajApiException(
    message: String,
    val status: Int? = null,
) : java.io.IOException(message)

/** The app token was missing, expired or revoked and must be re-fetched. */
class UnauthorizedException(message: String) : BajajApiException(message, status = 401)
