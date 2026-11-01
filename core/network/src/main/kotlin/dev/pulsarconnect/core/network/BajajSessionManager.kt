package dev.pulsarconnect.core.network

import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.session.SessionRepository
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the durable session.
 *
 * The user's VIN and access code are the real credentials and are persisted
 * once they are accepted. Tokens are an implementation detail: if one is
 * missing or rejected we fetch a new one and retry, once, with a single
 * in-flight refresh shared by all callers.
 */
class BajajSessionManager(
    private val api: BajajApi,
    private val repository: SessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val stateMutex = Mutex()
    private val refreshMutex = Mutex()

    private var current: StoredSession? = null
    private var loaded = false

    /** Loads the persisted session. Never touches the network. */
    suspend fun restore(): StoredSession? = stateMutex.withLock {
        if (!loaded) {
            current = repository.load()
            loaded = true
        }
        current
    }

    /** Obtains tokens, then persists the session only if they were accepted. */
    suspend fun signIn(credentials: Credentials): SignInResult {
        val appToken = api.whoAmI()

        return when (val outcome = api.logIn(credentials, appToken)) {
            is LoginOutcome.Success -> {
                val stored = StoredSession(
                    credentials = credentials,
                    session = Session(
                        accessToken = appToken,
                        userToken = outcome.userToken,
                        issuedAt = clock.instant(),
                    ),
                )
                persist(stored)
                SignInResult.Success(stored)
            }

            LoginOutcome.VehicleNotRegistered -> SignInResult.VehicleNotRegistered

            is LoginOutcome.Rejected -> SignInResult.Rejected(outcome.message)
        }
    }

    /**
     * Runs [block] with a valid session, refreshing the app token once if the
     * server rejects it. Concurrent rejections share one refresh.
     */
    suspend fun <T> withSession(block: suspend (StoredSession) -> T): T {
        val session = requireSession()
        return try {
            block(session)
        } catch (_: UnauthorizedException) {
            block(refreshAppToken(session.session.accessToken))
        }
    }

    suspend fun signOut(): Unit = stateMutex.withLock {
        repository.clear()
        current = null
        loaded = true
    }

    private suspend fun requireSession(): StoredSession =
        restore() ?: throw IllegalStateException("not signed in")

    private suspend fun refreshAppToken(staleToken: String): StoredSession =
        refreshMutex.withLock {
            val session = current ?: throw IllegalStateException("not signed in")

            // Another caller may have refreshed while this one waited.
            if (session.session.accessToken != staleToken) {
                return session
            }

            val freshToken = api.whoAmI()
            val refreshed = session.copy(
                session = session.session.copy(
                    accessToken = freshToken,
                    issuedAt = clock.instant(),
                ),
            )
            persist(refreshed)
            refreshed
        }

    private suspend fun persist(stored: StoredSession) = stateMutex.withLock {
        repository.save(stored)
        current = stored
        loaded = true
    }
}

sealed interface SignInResult {
    data class Success(val session: StoredSession) : SignInResult

    data object VehicleNotRegistered : SignInResult

    data class Rejected(val message: String) : SignInResult
}
