package io.karandash.platforms.bitbucket

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

/** The configured `url` is the API root, and `username` switches the token to HTTP Basic app-password auth. */
class BitbucketBaseUrlAuthTest {
    private fun client(
        seen: MutableList<HttpRequestData>,
        username: String?,
    ): BitbucketClient {
        val engine = MockEngine { request ->
            seen += request
            respond("""{"values":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return BitbucketClient(
            token = "app-pass",
            httpClient = http,
            responseCache = null,
            baseUrl = "https://bb.example.com/rest/2.0",
            username = username,
        )
    }

    @Test
    fun configuredUrlAndUsernameSendBasicAuthToThatHost() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            client(seen, username = "alice").listComments("ws", "repo", 1)
            val request = seen.single()
            assertEquals("bb.example.com", request.url.host)
            assertEquals("/rest/2.0/repositories/ws/repo/pullrequests/1/comments", request.url.encodedPath)
            val expected = "Basic " + Base64.encode("alice:app-pass".encodeToByteArray())
            assertEquals(expected, request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun withoutUsernameTheTokenIsABearerToken() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            client(seen, username = null).listComments("ws", "repo", 1)
            assertEquals("Bearer app-pass", seen.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun loopbackUrlNeedsAllowLocalhost() =
        runTest {
            assertFails { BitbucketClient(token = "t", responseCache = null, baseUrl = "http://localhost:8080/bb/2.0") }
            val client = BitbucketClient(
                token = "t",
                responseCache = null,
                baseUrl = "http://localhost:8080/bb/2.0",
                allowLocalhost = true,
            )
            assertNotNull(client)
            client.close()
        }

    @Test
    fun credentialsCarryUrlAndUsernameFromConfig() {
        val config = buildJsonObject {
            put("url", JsonPrimitive("https://bb.example.com/rest/2.0"))
            put("token", JsonPrimitive("app-pass"))
            put("username", JsonPrimitive("alice"))
        }
        val resolved = BitbucketCredentials.resolve(config) { null }
        assertEquals("https://bb.example.com/rest/2.0", resolved.baseUrl)
        assertEquals("alice", resolved.username)
        assertEquals(
            BitbucketConfig.DEFAULT_URL,
            BitbucketCredentials
                .resolve(
                    buildJsonObject {
                        put("token", JsonPrimitive("x"))
                    }
                ) { null }
                .baseUrl
        )
    }
}
