package dev.pulsarconnect.core.network

import dev.pulsarconnect.core.model.Credentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin, typed wrapper over the endpoints recovered from the official client.
 *
 * Every method here is a single request; retries and token refresh live in
 * [BajajSessionManager] so the transport stays easy to test.
 */
class BajajApi internal constructor(
    baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json,
) {

    constructor(baseUrl: String = BASE_URL) : this(baseUrl, defaultClient, defaultJson)

    private val baseUrl = baseUrl.trimEnd('/')

    /** `GET /api/whoami` — returns the app token used as the bearer everywhere. */
    suspend fun whoAmI(): String {
        val request = Request.Builder()
            .url("$baseUrl/api/whoami?subscription_id=$SUBSCRIPTION_ID")
            .get()
            .build()

        val payload = json.decodeFromString<WhoAmIResponse>(execute(request))
        return payload.token
            ?: throw BajajApiException(payload.message ?: "whoami returned no token")
    }

    /** `POST /api/ib/rc/login` — checks vehicle ownership with the dealer code. */
    suspend fun logIn(credentials: Credentials, appToken: String): LoginOutcome {
        val payload = json.decodeFromString<LoginResponse>(
            execute(credentialsRequest("/api/ib/rc/login", credentials, appToken)),
        )
        return when (payload.message) {
            SUCCESS -> LoginOutcome.Success(payload.token, payload.data)
            VEHICLE_NOT_REGISTERED -> LoginOutcome.VehicleNotRegistered
            null -> throw BajajApiException("login response contained no message")
            else -> LoginOutcome.Rejected(payload.message)
        }
    }

    /** `POST /api/ib/rc/validate` — the official app's cold-start gate. */
    suspend fun validate(credentials: Credentials, appToken: String): Boolean {
        val payload = json.decodeFromString<LoginResponse>(
            execute(credentialsRequest("/api/ib/rc/validate", credentials, appToken)),
        )
        return payload.message == SUCCESS
    }

    /**
     * `GET /api/ib/rc/customer/vehicles` — the bikes linked to this account.
     *
     * Unlike every other endpoint, the official client authorises this one
     * with the login token (`userToken`), not the whoami app token.
     */
    suspend fun vehicles(userToken: String): List<Bike> {
        val request = Request.Builder()
            .url("$baseUrl/api/ib/rc/customer/vehicles")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()

        return json.decodeFromString<VehiclesResponse>(execute(request)).data?.data.orEmpty()
    }

    private fun credentialsRequest(
        path: String,
        credentials: Credentials,
        appToken: String,
    ): Request {
        val body = json.encodeToString(
            LoginRequest(
                vinNumber = credentials.vin.value,
                accessCode = credentials.accessCode,
            ),
        )
        return Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $appToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (response.code == 401) {
                throw UnauthorizedException(body.ifBlank { "HTTP 401" })
            }
            if (!response.isSuccessful) {
                throw BajajApiException(
                    message = "HTTP ${response.code}: ${body.take(200)}",
                    status = response.code,
                )
            }
            if (body.declaresExpiredSession()) {
                throw UnauthorizedException(body)
            }
            body
        }
    }

    /**
     * The official client only inspects `message`, so a rejected token can
     * arrive as HTTP 200. Treat the known phrasings as a refresh trigger.
     */
    private fun String.declaresExpiredSession(): Boolean {
        val message = runCatching {
            json.parseToJsonElement(this).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()

        return EXPIRED_SESSION_PHRASES.any { message.contains(it, ignoreCase = true) }
    }

    @Serializable
    private data class WhoAmIResponse(
        val message: String? = null,
        val token: String? = null,
    )

    @Serializable
    private data class LoginRequest(
        @SerialName("vin_number") val vinNumber: String,
        @SerialName("access_code") val accessCode: String,
    )

    @Serializable
    private data class LoginResponse(
        val message: String? = null,
        val token: String? = null,
        val data: JsonElement? = null,
    )

    @Serializable
    private data class VehiclesResponse(
        val message: String? = null,
        val data: VehiclesData? = null,
    )

    @Serializable
    private data class VehiclesData(
        val data: List<Bike> = emptyList(),
    )

    companion object {
        /** `string/ZERSYS_URL` in the official APK. */
        const val BASE_URL = "https://fms.bajajauto.com"

        private const val SUBSCRIPTION_ID = "Bajaj_CDMS"
        private const val SUCCESS = "Success"
        private const val VEHICLE_NOT_REGISTERED = "Vehicle not registered"

        private val EXPIRED_SESSION_PHRASES = listOf(
            "session expired",
            "unauthorized",
            "invalid token",
        )

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
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

/** A motorcycle linked to the account. Field names mirror the server JSON. */
@Serializable
data class Bike(
    @SerialName("ChassisNo") val chassisNumber: String? = null,
    val model: String? = null,
    @SerialName("model_image") val modelImage: String? = null,
    val path: String? = null,
    val nickname: String? = null,
)

open class BajajApiException(
    message: String,
    val status: Int? = null,
) : java.io.IOException(message)

/** The app token was missing, expired or revoked and must be re-fetched. */
class UnauthorizedException(message: String) : BajajApiException(message, status = 401)
