package io.karandash.platforms.github

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Project membership is write access on the collaborator permission endpoint; 404 means not a member. */
class GitHubMembershipTest {
    private val paths = mutableListOf<String>()

    private fun client(): GitHubClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            when {
                path.contains("/admin/") -> respond("""{"permission":"admin"}""", HttpStatusCode.OK, JSON)
                path.contains("/triage/") -> respond("""{"permission":"read"}""", HttpStatusCode.OK, JSON)
                path == "/user" -> respond("""{"login":"bot","id":1}""", HttpStatusCode.OK, JSON)
                else -> respond("{}", HttpStatusCode.NotFound, JSON)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitHubClient(baseUrl = "https://api.github.com", token = "t", httpClient = http, responseCache = null)
    }

    @Test
    fun `admin is a member and read or 404 is not`() =
        runTest {
            val github = client()

            assertTrue(github.hasWriteAccess("o", "r", "admin"))
            assertFalse(github.hasWriteAccess("o", "r", "triage"))
            assertFalse(github.hasWriteAccess("o", "r", "stranger"))
            assertEquals("/repos/o/r/collaborators/admin/permission", paths.first())
        }

    @Test
    fun `the current username is the token user login`() =
        runTest {
            assertEquals("bot", client().fetchCurrentUsername())
        }

    private companion object {
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
