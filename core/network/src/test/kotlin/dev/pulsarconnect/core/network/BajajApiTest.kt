package dev.pulsarconnect.core.network

import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.Vin
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * These tests pin the request shapes recovered from the official client so a
 * refactor cannot silently change them. The bodies are synthetic.
 */
class BajajApiTest {

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

    @Test
    fun `whoami asks for the Bajaj CDMS subscription and returns its token`() = runTest {
        server.enqueue(json("""{"message":"Success","token":"app-token"}"""))

        val token = api().whoAmI()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/whoami?subscription_id=Bajaj_CDMS", request.path)
        assertEquals("app-token", token)
    }

    @Test
    fun `whoami fails when the server does not return a token`() = runTest {
        server.enqueue(json("""{"message":"Something went wrong"}"""))

        val error = runCatching { api().whoAmI() }.exceptionOrNull()

        assertTrue(error is BajajApiException, "expected BajajApiException, got $error")
    }

    @Test
    fun `login posts the vehicle credentials with the app token`() = runTest {
        server.enqueue(
            json("""{"message":"Success","token":"user-token","data":{"name":"Test Rider"}}"""),
        )

        val outcome = api().logIn(credentials, appToken = "app-token")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/ib/rc/login", request.path)
        assertEquals("Bearer app-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals(
            """{"vin_number":"MD2ABCDEFGH123456","access_code":"test-access-code"}""",
            request.body.readUtf8(),
        )

        assertTrue(outcome is LoginOutcome.Success, "was $outcome")
        outcome as LoginOutcome.Success
        assertEquals("user-token", outcome.userToken)
    }

    @Test
    fun `login reports an unregistered vehicle as its own outcome`() = runTest {
        server.enqueue(json("""{"message":"Vehicle not registered"}"""))

        assertEquals(LoginOutcome.VehicleNotRegistered, api().logIn(credentials, "app-token"))
    }

    @Test
    fun `login keeps the server message when the access code is rejected`() = runTest {
        server.enqueue(json("""{"message":"Invalid access code"}"""))

        assertEquals(
            LoginOutcome.Rejected("Invalid access code"),
            api().logIn(credentials, "app-token"),
        )
    }

    @Test
    fun `validate posts the stored credentials with the app token`() = runTest {
        server.enqueue(json("""{"message":"Success"}"""))

        assertTrue(api().validate(credentials, appToken = "app-token"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/ib/rc/validate", request.path)
        assertEquals("Bearer app-token", request.getHeader("Authorization"))
    }

    @Test
    fun `validate returns false when the server rejects the credentials`() = runTest {
        server.enqueue(json("""{"message":"Invalid access code"}"""))

        assertTrue(!api().validate(credentials, "app-token"))
    }

    @Test
    fun `a 401 becomes an unauthorized error so the caller can refresh`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message":"Session Expired"}"""),
        )

        val error = runCatching { api().validate(credentials, "stale") }.exceptionOrNull()

        assertTrue(error is UnauthorizedException, "expected UnauthorizedException, got $error")
    }

    @Test
    fun `a 200 carrying Session Expired also becomes an unauthorized error`() = runTest {
        server.enqueue(json("""{"message":"Session Expired , Please login again"}"""))

        val error = runCatching { api().validate(credentials, "stale") }.exceptionOrNull()

        assertTrue(error is UnauthorizedException, "expected UnauthorizedException, got $error")
    }
}
