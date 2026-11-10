package io.karandash.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostScopedAuthTest {
    private val seen = mutableMapOf<String, String?>()

    private fun engine(header: String) =
        MockEngine { request ->
            seen[request.url.host] = request.headers[header]
            if (request.url.host == "gitlab.example.com" && request.url.encodedPath == "/moved") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://attacker.example.net/x"))
            } else if (request.url.host == "gitlab.example.com" && request.url.encodedPath == "/moved-keyed") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example.org/x"))
            } else {
                respond("{}", HttpStatusCode.OK)
            }
        }

    @Test
    fun `keyed host gets the credential and another host does not`() =
        runTest {
            val creds = HostCredentials.of("https://gitlab.example.com", HttpAuth.CustomHeader("PRIVATE-TOKEN", "glpat"))
            val client = ProviderHttpClientFactory.create(engine = engine("PRIVATE-TOKEN"), credentials = creds)

            client.get("https://gitlab.example.com/api/v4/user")
            client.get("https://attacker.example.net/api/v4/user") { header("PRIVATE-TOKEN", "glpat") }

            assertEquals("glpat", seen["gitlab.example.com"])
            assertNull(seen["attacker.example.net"])
        }

    @Test
    fun `redirect to another host drops the credential`() =
        runTest {
            val creds = HostCredentials.of("https://gitlab.example.com", HttpAuth.Bearer("secret"))
            val client = ProviderHttpClientFactory.create(engine = engine(HttpHeaders.Authorization), credentials = creds)

            client.get("https://gitlab.example.com/moved")

            assertEquals("Bearer secret", seen["gitlab.example.com"])
            assertNull(seen["attacker.example.net"])
        }

    @Test
    fun `redirect between two keyed hosts swaps in the credential of the target host`() =
        runTest {
            val creds =
                HostCredentials
                    .of("https://gitlab.example.com", HttpAuth.CustomHeader("PRIVATE-TOKEN", "token-a"))
                    .plus(HostCredentials.of("https://other.example.org", HttpAuth.CustomHeader("PRIVATE-TOKEN", "token-b")))
            val client = ProviderHttpClientFactory.create(engine = engine("PRIVATE-TOKEN"), credentials = creds)

            client.get("https://gitlab.example.com/moved-keyed")

            assertEquals("token-a", seen["gitlab.example.com"])
            assertEquals("token-b", seen["other.example.org"])
        }

    @Test
    fun `explicit header on the keyed host survives`() =
        runTest {
            val creds = HostCredentials.of("https://api.github.com", HttpAuth.Bearer("installation"))
            val client = ProviderHttpClientFactory.create(engine = engine(HttpHeaders.Authorization), credentials = creds)

            client.get("https://api.github.com/app/installations") { header(HttpHeaders.Authorization, "Bearer jwt") }

            assertEquals("Bearer jwt", seen["api.github.com"])
        }

    @Test
    fun `explicit header survives on a keyed host whose credential is not yet available`() =
        runTest {
            val creds = HostCredentials.of("https://api.github.com") { null }
            val client = ProviderHttpClientFactory.create(engine = engine(HttpHeaders.Authorization), credentials = creds)

            client.get("https://api.github.com/app/installations/1/access_tokens") { header(HttpHeaders.Authorization, "Bearer jwt") }
            client.get("https://attacker.example.net/x") { header(HttpHeaders.Authorization, "Bearer jwt") }

            assertEquals("Bearer jwt", seen["api.github.com"])
            assertNull(seen["attacker.example.net"])
        }

    @Test
    fun `port is part of the key`() {
        val creds = HostCredentials.of("https://git.example.com:8443", HttpAuth.Bearer("t"))

        assertEquals(HttpAuth.Bearer("t"), creds.authFor("https://GIT.example.com:8443/api"))
        assertNull(creds.authFor("https://git.example.com/api"))
    }

    @Test
    fun `http auth serializes with the extends type names and masks secrets`() {
        val json = Json.encodeToString(HttpAuth.serializer(), HttpAuth.Basic("u", "p"))

        assertEquals("""{"type":"basic-auth","username":"u","password":"p"}""", json)
        assertEquals(HttpAuth.Basic("u", "p"), Json.decodeFromString(HttpAuth.serializer(), json))
        assertEquals("Bearer(token=***)", HttpAuth.Bearer("secret").toString())
    }
}
