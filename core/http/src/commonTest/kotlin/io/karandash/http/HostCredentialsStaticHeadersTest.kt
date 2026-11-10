package io.karandash.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The header names stripped on a cross-host request are fixed per entry when the
 * credentials are built, so a credential that is null right now is still stripped and no provider runs for a request
 * to a host it is not keyed for.
 */
class HostCredentialsStaticHeadersTest {
    @Test
    fun `a credential that is null right now still has its header stripped on another host`() =
        runTest {
            val creds = HostCredentials.of("https://gitlab.example.com", setOf("PRIVATE-TOKEN")) { null }
            assertEquals(setOf("PRIVATE-TOKEN"), creds.headerNames())
            val seen = mutableMapOf<String, String?>()
            val engine = MockEngine { request ->
                seen[request.url.host] = request.headers["PRIVATE-TOKEN"]
                respond("{}", HttpStatusCode.OK)
            }
            val client = ProviderHttpClientFactory.create(engine = engine, credentials = creds)
            client.get("https://attacker.example.net/x") { header("PRIVATE-TOKEN", "glpat") }
            client.close()
            assertEquals(mapOf<String, String?>("attacker.example.net" to null), seen)
        }

    @Test
    fun `a request to an unkeyed host runs no credential provider`() =
        runTest {
            val calls = mutableListOf<String>()
            val creds = HostCredentials.of("https://api.github.com") {
                calls += "github"
                HttpAuth.Bearer("t")
            }
            val engine = MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Authorization])
                respond("{}", HttpStatusCode.OK)
            }
            val client = ProviderHttpClientFactory.create(engine = engine, credentials = creds)
            client.get("https://other.example.com/x")
            client.close()
            assertEquals(setOf(HttpHeaders.Authorization), creds.headerNames())
            assertEquals(emptyList(), calls)
        }
}
