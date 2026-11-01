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
                persist(
                    StoredSession(
                        credentials = credentials,
                        session = Session(
                            accessToken = appToken,
                            userToken = outcome.userToken,
                            issuedAt = clock.instant(),
                        ),
                    ),
                )
                SignInResult.Success(current!!)
            }

            LoginOutcome.VehicleNotRegistered -> SignInResult.VehicleNotRegistered

            is LoginOutcome.Rejected -> SignInResult.Rejected(outcome.message)
        }
    }

    suspend fun <T> withSession(block: suspend (StoredSession) -> T): T {
        val session = requireSession()

        try {
            return block(session)
        } catch (_: UnauthorizedException) {
            // First try: refresh the app token
            val afterAppRefresh = refreshAppToken(session.session.accessToken)
            try {
                return block(afterAppRefresh)
            } catch (_: UnauthorizedException) {
                // Second try: if the app token refresh didn't help, the user token is stale
                // Re-authenticate with the credentials to get a fresh user token
                val reauthenticated = signIn(session.credentials)
                require(reauthenticated is SignInResult.Success)
                return block(reauthenticated.session)
            }
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
            persist(
                session.copy(
                    session = session.session.copy(
                        accessToken = freshToken,
                        issuedAt = clock.instant(),
                    ),
                ),
            )
            current!!
        }

    private suspend fun reauthenticate(staleUserToken: String?): StoredSession {
        requireSession() // ensure we have credentials
        return refreshMutex.withLock {
            // Another caller may have reauthenticated while this one waited.
            if (current?.session?.userToken == staleUserToken) {
                return current!!
            }

            val result = signIn(current!!.credentials)
            require(result is SignInResult.Success)
            result.session
        }
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