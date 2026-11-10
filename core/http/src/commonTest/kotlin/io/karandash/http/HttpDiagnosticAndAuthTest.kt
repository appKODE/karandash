@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.http

import io.karandash.api.ModuleRegistryImpl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpDiagnosticAndAuthTest {
    private data class Call(
        val method: String,
        val url: String,
        val status: Int?,
        val size: Long?,
        val error: String?,
    )

    @Test
    fun `the diagnostic listener sees successes and failures without leaking more than 200 chars`() =
        runTest {
            val calls = mutableListOf<Call>()
            val listener = HttpDiagnosticListener { method, url, status, durationMs, size, error ->
                assertTrue(durationMs >= 0)
                calls += Call(method, url, status, size, error)
            }
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/fail") throw IOException("x".repeat(300))
                respond("hello", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
            }
            val session = ModuleRegistryImpl().forRun()
            session.provide(ProviderHttpClientFactory.DIAGNOSTIC_LISTENER, listener)
            val client = ProviderHttpClientFactory.create(engine = engine, services = session.services)
            client.get("https://example.com/ok")
            assertFails { client.get("https://example.com/fail") }
            client.close()

            assertEquals(Call("GET", "https://example.com/ok", 200, 5, null), calls.first())
            val failure = calls.last()
            assertEquals("https://example.com/fail", failure.url)
            assertNull(failure.status)
            assertEquals("x".repeat(200), failure.error)
        }

    @Test
    fun `each credential type sets its own header and hides its secret`() {
        val cases = listOf(
            HttpAuth.Bearer("t") to (HttpHeaders.Authorization to "Bearer t"),
            HttpAuth.Basic("u", "p") to (HttpHeaders.Authorization to "Basic dTpw"),
            HttpAuth.CustomHeader("PRIVATE-TOKEN", "g") to ("PRIVATE-TOKEN" to "g"),
        )
        cases.forEach { (auth, header) ->
            val request = HttpRequestBuilder()
            request.headers[header.first] = "old"
            request.applyAuth(auth)

            assertEquals(header.second, request.headers[header.first])
            assertFalse(auth.toString().contains(header.second), auth.toString())
            val json = Json.encodeToString(HttpAuth.serializer(), auth)
            assertEquals(auth, Json.decodeFromString(HttpAuth.serializer(), json))
        }
        val untouched = HttpRequestBuilder().apply { applyAuth(null) }
        assertNull(untouched.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `host credentials are keyed by host and port`() {
        val auth = HttpAuth.Bearer("t")
        val creds = HostCredentials.of("https://git.example.com", auth)

        assertSame(HostCredentials.EMPTY, HostCredentials.of("https://git.example.com", null))
        assertEquals(auth, creds.authFor("https://git.example.com/api"))
        assertNull(creds.authFor("https://other.example.com/api"))
        assertTrue(creds.isKeyed(Url("https://git.example.com/x")))
        assertFalse(creds.isKeyed(Url("https://git.example.com:8443/x")))
        assertEquals(setOf(HttpHeaders.Authorization), creds.headerNames())
        assertTrue(HostCredentials.sameHost("https://git.example.com/a", "https://git.example.com"))
        assertFalse(HostCredentials.sameHost("https://git.example.com:8443/a", "https://git.example.com"))
        assertFalse(HostCredentials.sameHost("https://other.example.com", "https://git.example.com"))
        assertEquals(setOf(HttpHeaders.Authorization), HostCredentials.of("https://h.example.com") { null }.headerNames())
    }
}
