package dev.pulsarconnect.core.network

import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.session.SessionRepository
import java.time.Clock

/**
 * Owns the durable session.
 *
 * The user's VIN and access code are the real credentials and are persisted
 * before any network call. Tokens are an implementation detail: if one is
 * missing or rejected we fetch a new one and retry, once, with a single
 * in-flight refresh shared by all callers.
 */
class BajajSessionManager(
    @Suppress("UNUSED_PARAMETER") private val api: BajajApi,
    @Suppress("UNUSED_PARAMETER") private val repository: SessionRepository,
    @Suppress("UNUSED_PARAMETER") private val clock: Clock = Clock.systemUTC(),
) {

    /** Loads the persisted session. Never touches the network. */
    suspend fun restore(): StoredSession? = TODO("not implemented")

    /** Persists [credentials] first, then obtains tokens. */
    suspend fun signIn(credentials: Credentials): SignInResult = TODO("not implemented")

    /**
     * Runs [block] with a valid session, refreshing the app token once if the
     * server rejects it. Concurrent rejections share one refresh.
     */
    suspend fun <T> withSession(block: suspend (StoredSession) -> T): T =
        TODO("not implemented")

    suspend fun signOut(): Unit = TODO("not implemented")
}

sealed interface SignInResult {
    data class Success(val session: StoredSession) : SignInResult

    data object VehicleNotRegistered : SignInResult

    data class Rejected(val message: String) : SignInResult
}
