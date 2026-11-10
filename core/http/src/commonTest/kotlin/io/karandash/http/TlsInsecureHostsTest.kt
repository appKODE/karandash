package io.karandash.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TlsInsecureHostsTest {
    private val gitlab = HostCredentials.of("https://203.0.113.10", HttpAuth.CustomHeader("PRIVATE-TOKEN", "glpat"))

    @Test
    fun `hosts are split on commas trimmed and lowercased`() {
        val env = mapOf(TLS_INSECURE_HOSTS_ENV to " 203.0.113.10 , Git.Example.COM,,")
        assertEquals(setOf("203.0.113.10", "git.example.com"), tlsInsecureHosts(env::get))
        assertEquals(emptySet(), tlsInsecureHosts { null })
    }

    @Test
    fun `only a client keyed for listed hosts alone skips verification`() {
        val listed = setOf("203.0.113.10")
        val openRouter = HostCredentials.of("https://openrouter.ai/api/v1", HttpAuth.Bearer("key"))

        assertTrue(skipsTlsVerification(gitlab, listed))
        assertFalse(skipsTlsVerification(openRouter, listed))
        assertFalse(skipsTlsVerification(gitlab + openRouter, listed))
        assertFalse(skipsTlsVerification(null, listed))
        assertFalse(skipsTlsVerification(gitlab, emptySet()))
    }

    @Test
    fun `guard refuses an unlisted host and a redirect to one`() =
        runTest {
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/moved") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://attacker.example.net/x"))
                } else {
                    respond("{}", HttpStatusCode.OK)
                }
            }
            val client = HttpClient(engine) { install(tlsInsecureHostGuard(setOf("203.0.113.10"))) }

            assertEquals(HttpStatusCode.OK, client.get("https://203.0.113.10/api/v4/user").status)
            assertFailsWith<IOException> { client.get("https://openrouter.ai/api/v1/chat") }
            assertFailsWith<IOException> { client.get("https://203.0.113.10/moved") }
        }
}
