package io.karandash.platforms.gitlab

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabReviewersTest {
    private val mr = "/api/v4/projects/42/merge_requests/7"

    private fun user(
        id: Int,
        name: String,
    ) = """{"id":$id,"username":"$name","name":"$name"}"""

    private fun mrJson(vararg reviewers: String) =
        """{"id":1,"iid":7,"title":"t","description":null,"source_branch":"f","target_branch":"main",""" +
            """"author":${user(1, "bot")},"state":"opened","reviewers":[${reviewers.joinToString(",")}]}"""

    /** Answers `"METHOD path"` from [routes], 404 for anything else; records `"METHOD path?query body"`. */
    private suspend fun service(
        calls: MutableList<String>,
        routes: Map<String, String>,
    ): GitLabPlatformService {
        val engine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            calls += "$key?${request.url.encodedQuery} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
            val body = routes[key]
            val status = if (body == null) HttpStatusCode.NotFound else HttpStatusCode.OK
            respond(body ?: "{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitLabPlatformService()
        service.client.rebuild("test") { GitLabClient(token = "t", httpClient = http, responseCache = null) }
        return service
    }

    @Test
    fun `addReviewers looks up the username and keeps the current reviewers`() =
        runTest {
            val calls = mutableListOf<String>()
            val service = service(
                calls,
                mapOf("GET $mr" to mrJson(user(3, "carol")), "GET /api/v4/users" to "[${user(5, "bob")}]", "PUT $mr" to "{}"),
            )

            assertTrue(service.addReviewers("42", "7", listOf("bob")).isSuccess)

            assertTrue(calls.any { it.startsWith("GET /api/v4/users?username=bob") })
            assertEquals("""PUT $mr? {"reviewer_ids":[3,5]}""", calls.last())
        }

    @Test
    fun `removeReviewers drops only the named reviewers`() =
        runTest {
            val calls = mutableListOf<String>()
            val service = service(calls, mapOf("GET $mr" to mrJson(user(3, "carol"), user(5, "bob")), "PUT $mr" to "{}"))

            assertTrue(service.removeReviewers("42", "7", listOf("bob")).isSuccess)

            assertEquals("""PUT $mr? {"reviewer_ids":[3]}""", calls.last())
            assertTrue(calls.none { it.contains("/users") })
        }

    @Test
    fun `addReviewers fails for an unknown username without updating the MR`() =
        runTest {
            val calls = mutableListOf<String>()
            val service = service(calls, mapOf("GET $mr" to mrJson(), "GET /api/v4/users" to "[]", "PUT $mr" to "{}"))

            assertFalse(service.addReviewers("42", "7", listOf("ghost")).isSuccess)
            assertTrue(calls.none { it.startsWith(HttpMethod.Put.value) })
        }

    @Test
    fun `reviewer usernames match case-insensitively`() =
        runTest {
            val calls = mutableListOf<String>()
            val service = service(
                calls,
                mapOf("GET $mr" to mrJson(user(3, "carol")), "GET /api/v4/users" to "[${user(5, "bob")}]", "PUT $mr" to "{}"),
            )
            val removeCalls = mutableListOf<String>()
            val removing = service(removeCalls, mapOf("GET $mr" to mrJson(user(3, "carol"), user(5, "bob")), "PUT $mr" to "{}"))

            assertTrue(service.addReviewers("42", "7", listOf("Bob")).isSuccess)
            assertTrue(removing.removeReviewers("42", "7", listOf("Bob")).isSuccess)

            assertEquals("""PUT $mr? {"reviewer_ids":[3,5]}""", calls.last())
            assertEquals("""PUT $mr? {"reviewer_ids":[3]}""", removeCalls.last())
        }
}
