package io.karandash.platforms.gitea

import io.karandash.api.exception.ProviderApiException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Project membership is write access on the collaborator permission endpoint; 404 means not a member. */
class GiteaMembershipTest {
    private val paths = mutableListOf<String>()

    private fun client(answers: Map<String, Pair<HttpStatusCode, String>>): GiteaClient {
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            val (status, body) = answers.entries.first { request.url.encodedPath.contains(it.key) }.value
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GiteaClient(baseUrl = "https://gitea.example.com", token = "t", httpClient = http)
    }

    @Test
    fun `write and owner are members and read is not`() =
        runTest {
            val gitea = client(
                mapOf(
                    "/writer/" to (HttpStatusCode.OK to """{"permission":"write"}"""),
                    "/owner/" to (HttpStatusCode.OK to """{"permission":"owner"}"""),
                    "/reader/" to (HttpStatusCode.OK to """{"permission":"read"}"""),
                ),
            )

            assertTrue(gitea.hasWriteAccess("o", "r", "writer"))
            assertTrue(gitea.hasWriteAccess("o", "r", "owner"))
            assertFalse(gitea.hasWriteAccess("o", "r", "reader"))
            assertEquals("/api/v1/repos/o/r/collaborators/writer/permission", paths.first())
        }

    @Test
    fun `a 404 is not a member and a 500 is thrown`() =
        runTest {
            val gitea = client(
                mapOf(
                    "/ghost/" to (HttpStatusCode.NotFound to "{}"),
                    "/boom/" to (HttpStatusCode.InternalServerError to "{}"),
                ),
            )

            assertFalse(gitea.hasWriteAccess("o", "r", "ghost"))
            assertFailsWith<ProviderApiException> { gitea.hasWriteAccess("o", "r", "boom") }
        }
}
