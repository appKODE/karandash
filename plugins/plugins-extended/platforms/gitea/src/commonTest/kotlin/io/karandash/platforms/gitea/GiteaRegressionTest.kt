package io.karandash.platforms.gitea

import io.karandash.api.finding.InlineCommentData
import io.karandash.models.DiffRefs
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Gitea approvals, env names, comment client token, token gate. */
class GiteaRegressionTest {
    private val author = GiteaUser(id = 1, login = "author")
    private val reviewer = GiteaUser(id = 2, login = "rev")

    private fun approvalsOf(vararg reviews: GiteaReview): List<String> {
        val pr = GiteaPullRequest(
            id = 1,
            number = 1,
            title = "t",
            body = null,
            htmlUrl = "https://gitea.com/o/r/pulls/1",
            state = "open",
            user = author,
            head = GiteaBranch(ref = "f", sha = "a"),
            base = GiteaBranch(ref = "main", sha = "b"),
            createdAt = "2024-01-01T10:00:00Z",
            updatedAt = "2024-01-01T10:00:00Z",
        )
        return GiteaAdapter.toMergeRequest(pr, emptyList(), emptyList(), reviews.toList()).approvals.map { it.user.username }
    }

    @Test
    fun `a later request for changes cancels an earlier approval`() {
        val approved = GiteaReview(id = 1, user = reviewer, state = "APPROVED")
        val rejected = GiteaReview(id = 2, user = reviewer, state = "REQUEST_CHANGES")
        assertEquals(emptyList(), approvalsOf(approved, rejected))
    }

    @Test
    fun `repeated approvals by one user count once and a comment keeps the approval`() {
        val first = GiteaReview(id = 1, user = reviewer, state = "APPROVED")
        val second = GiteaReview(id = 2, user = reviewer, state = "APPROVED")
        val comment = GiteaReview(id = 3, user = reviewer, state = "COMMENT")
        assertEquals(listOf("rev"), approvalsOf(first, second, comment))
    }

    @Test
    fun `dismissed and stale approvals do not count`() {
        val dismissed = GiteaReview(id = 1, user = reviewer, state = "APPROVED", dismissed = true)
        val stale = GiteaReview(id = 2, user = GiteaUser(id = 3, login = "other"), state = "APPROVED", stale = true)
        assertEquals(emptyList(), approvalsOf(dismissed, stale))
    }

    @Test
    fun `a dismissed request for changes does not revive an earlier approval`() {
        val approved = GiteaReview(id = 1, user = reviewer, state = "APPROVED")
        val rejected = GiteaReview(id = 2, user = reviewer, state = "REQUEST_CHANGES", dismissed = true)
        assertEquals(emptyList(), approvalsOf(approved, rejected))
    }

    @Test
    fun `inline comments of a run go out as one review`() =
        runTest {
            val server =
                RecordingMockServer(
                    listOf(route(HttpMethod.Post, "/api/v1/repos/o/demo/pulls/7/reviews", """{"id":5,"state":"COMMENT"}"""))
                )
            val service = GiteaPlatformService(server.engine)
            val config = JsonObject(mapOf("url" to JsonPrimitive("https://gitea.example.com"), "token" to JsonPrimitive("t")))
            assertTrue(service.initialize(TestPluginContext(config)))

            val id = service.postInlineComments(
                "o/demo",
                "7",
                listOf(InlineCommentData("a.kt", 2, body = "one"), InlineCommentData("b.kt", 3, body = "two")),
                DiffRefs("b", "h1", "b"),
            )

            assertEquals("5", id)
            val body = (server.requests().single().body as TextContent).text
            assertEquals(2, Regex("new_position").findAll(body).count())
            assertTrue("a.kt" in body && "b.kt" in body)
        }

    @Test
    fun `credentials accept GITEA_SERVER_URL`() {
        val env = mapOf("GITEA_SERVER_URL" to "https://gitea.example.com/")
        assertEquals("https://gitea.example.com", GiteaCredentials.resolve(JsonObject(emptyMap())) { env[it] }?.baseUrl)
    }

    @Test
    fun `CI detection accepts GITEA_URL`() =
        runTest {
            val env = mapOf("GITEA_URL" to "https://gitea.example.com")
            assertEquals("https://gitea.example.com", GiteaPlatformService().detectCiEnvironment { env[it] }?.baseUrl)
        }

    @Test
    fun `comment client keeps the configured token on re-initialize`() =
        runTest {
            val server =
                RecordingMockServer(listOf(route(HttpMethod.Post, "/api/v1/repos/o/demo/issues/7/comments", """{"id":21,"body":"v"}""")))
            val service = GiteaPlatformService(server.engine)
            val url = JsonPrimitive("https://gitea.example.com")
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("url" to url, "token" to JsonPrimitive("t"))))))
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("url" to url)))))

            service.postComment("o/demo", "7", "hello")

            assertEquals("token t", server.requests().single().headers["Authorization"])
        }

    @Test
    fun `a Gitea write without a token fails before any request`() =
        runTest {
            val server = RecordingMockServer(emptyList())
            val service = GiteaPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("url" to JsonPrimitive("https://gitea.example.com"))))))

            assertFailsWith<IllegalStateException> { service.postComment("o/demo", "7", "hello") }
            assertEquals(0, server.requests().size)
        }
}
