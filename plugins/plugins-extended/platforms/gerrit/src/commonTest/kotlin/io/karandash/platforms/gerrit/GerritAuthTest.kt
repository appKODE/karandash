package io.karandash.platforms.gerrit

import io.karandash.api.exception.ProviderApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GerritAuthTest {
    private val change = """)]}'
{"id": "p~master~I1", "_number": 7, "change_id": "I1", "subject": "s", "project": "p", "branch": "master",
 "status": "NEW", "owner": {"_account_id": 1, "name": "Dev"}}"""

    private suspend fun recorded(call: suspend (HttpClient) -> Unit): HttpRequestData {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(
            MockEngine { request ->
                seen += request
                respond(change, HttpStatusCode.OK)
            },
        )
        runCatching { call(http) }
        return seen.first()
    }

    private suspend fun providerRequest(
        username: String?,
        token: String?
    ) = recorded { GerritProviderClient("https://gerrit.example.com", it, token, username = username).fetchChange("p", "7") }

    private suspend fun commentRequest(
        username: String?,
        token: String?
    ) = recorded { GerritClient("https://gerrit.example.com", token, it, username = username).getChange("p", "7") }

    private fun assertBasic(request: HttpRequestData) {
        assertTrue(request.url.encodedPath.startsWith("/a/changes/"), "was ${request.url.encodedPath}")
        val header = request.headers[HttpHeaders.Authorization].orEmpty()
        assertTrue(header.startsWith("Basic "), "was $header")
        assertEquals("alice:http-pass", Base64.decode(header.removePrefix("Basic ")).decodeToString())
    }

    private fun assertAnonymous(request: HttpRequestData) {
        assertTrue(request.url.encodedPath.startsWith("/changes/"), "was ${request.url.encodedPath}")
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun rejectedCredentialsOnFetchChangeRaiseProviderApiException() =
        runTest {
            val http = HttpClient(MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) })
            val client = GerritProviderClient("https://gerrit.example.com", http, "wrong", username = "alice")

            val e = assertFailsWith<ProviderApiException> { client.fetchChange("p", "7") }

            assertEquals(401, e.statusCode)
        }

    @Test
    fun providerClientUsesBasicAuthUnderAPrefix() = runTest { assertBasic(providerRequest("alice", "http-pass")) }

    @Test
    fun commentClientUsesBasicAuthUnderAPrefix() = runTest { assertBasic(commentRequest("alice", "http-pass")) }

    @Test
    fun clientsWithoutUsernameAreAnonymous() =
        runTest {
            assertAnonymous(providerRequest(null, "http-pass"))
            assertAnonymous(commentRequest(null, "http-pass"))
        }

    @Test
    fun clientsWithoutCredentialsAreAnonymous() =
        runTest {
            assertAnonymous(providerRequest(null, null))
            assertAnonymous(commentRequest(null, null))
        }

    @Test
    fun credentialsResolveUsernameFromConfigThenEnv() {
        val env = mapOf("GERRIT_URL" to "https://g.example.com", "GERRIT_USERNAME" to "env-user", "GERRIT_TOKEN" to "t")
        assertEquals("env-user", GerritCredentials.resolve(JsonObject(emptyMap()), env::get)?.username)
        val config = JsonObject(mapOf("username" to JsonPrimitive("cfg-user")))
        assertEquals("cfg-user", GerritCredentials.resolve(config, env::get)?.username)
    }
}
