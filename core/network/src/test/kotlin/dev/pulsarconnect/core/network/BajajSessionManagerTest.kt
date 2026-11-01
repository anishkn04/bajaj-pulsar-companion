package dev.pulsarconnect.core.network

import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BajajSessionManagerTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = BajajApi(baseUrl = server.url("/").toString())

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val credentials = Credentials(
        vin = Vin.of("MD2ABCDEFGH123456"),
        accessCode = "test-access-code",
    )

    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `restores a stored session without touching the network`() = runTest {
        val secrets = MapSecretStore()
        val stored = StoredSession(
            credentials = credentials,
            session = Session(accessToken = "app-token", userToken = "user-token", issuedAt = now),
        )
        SessionRepository(secrets).save(stored)

        val manager = BajajSessionManager(api(), SessionRepository(secrets), clock)

        assertEquals(stored, manager.restore())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signing in keeps the credentials and tokens for the next launch`() = runTest {
        server.enqueue(json("""{"message":"Success","token":"app-token"}"""))
        server.enqueue(json("""{"message":"Success","token":"user-token","data":{}}"""))

        val secrets = MapSecretStore()
        val manager = BajajSessionManager(api(), SessionRepository(secrets), clock)

        val result = manager.signIn(credentials)

        assertTrue(result is SignInResult.Success, "was $result")
        assertEquals(credentials, (result as SignInResult.Success).session.credentials)

        // A brand new manager over the same bytes is what a process restart
        // looks like, and it must not need the network.
        val afterRestart = BajajSessionManager(api(), SessionRepository(secrets), clock)
        val restored = afterRestart.restore()

        assertEquals(credentials, restored?.credentials)
        assertEquals("app-token", restored?.session?.accessToken)
        assertEquals("user-token", restored?.session?.userToken)
        assertEquals(now, restored?.session?.issuedAt)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an unregistered vehicle is reported and nothing is persisted`() = runTest {
        server.enqueue(json("""{"message":"Success","token":"app-token"}"""))
        server.enqueue(json("""{"message":"Vehicle not registered"}"""))

        val secrets = MapSecretStore()
        val manager = BajajSessionManager(api(), SessionRepository(secrets), clock)

        assertEquals(SignInResult.VehicleNotRegistered, manager.signIn(credentials))
        assertNull(SessionRepository(secrets).load())
    }

    @Test
    fun `a revoked access code is reported with the server message`() = runTest {
        server.enqueue(json("""{"message":"Success","token":"app-token"}"""))
        server.enqueue(json("""{"message":"Invalid access code"}"""))

        val manager = BajajSessionManager(api(), SessionRepository(MapSecretStore()), clock)

        assertEquals(SignInResult.Rejected("Invalid access code"), manager.signIn(credentials))
    }

    @Test
    fun `concurrent rejected calls refresh the app token exactly once`() = runTest {
        val whoAmICalls = AtomicInteger()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val authorization = request.getHeader("Authorization")
                return when {
                    path.startsWith("/api/whoami") -> {
                        whoAmICalls.incrementAndGet()
                        json("""{"message":"Success","token":"fresh"}""")
                    }

                    authorization == "Bearer fresh" -> json("""{"message":"Success"}""")

                    else -> MockResponse()
                        .setResponseCode(401)
                        .setBody("""{"message":"Session Expired"}""")
                }
            }
        }

        val secrets = MapSecretStore()
        SessionRepository(secrets).save(
            StoredSession(
                credentials = credentials,
                session = Session(accessToken = "stale", issuedAt = now),
            ),
        )
        val manager = BajajSessionManager(api(), SessionRepository(secrets), clock)

        val results = coroutineScope {
            (1..4).map {
                async {
                    manager.withSession { session ->
                        api().validate(credentials, session.session.accessToken)
                    }
                }
            }.awaitAll()
        }

        assertTrue(results.all { it }, "every caller should eventually succeed: $results")
        assertEquals(1, whoAmICalls.get(), "token refresh must be single-flight")
    }

    private class MapSecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
        private val values = initial.toMutableMap()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun delete(key: String) {
            values.remove(key)
        }
    }
}
