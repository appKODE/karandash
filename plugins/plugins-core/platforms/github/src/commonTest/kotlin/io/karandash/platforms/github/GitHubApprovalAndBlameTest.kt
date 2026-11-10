package io.karandash.platforms.github

import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User
import io.karandash.testfixtures.MockRoute
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

class GitHubApprovalAndBlameTest {
    @Test
    fun `approve posts an APPROVE review when the bot has not approved yet`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            service(server).approve("o/r", "7")

            assertEquals(listOf("GET /user", "GET $PR/reviews", "POST $PR/reviews"), calls(server))
            assertEquals("""{"event":"APPROVE"}""", (server.requests().last().body as TextContent).text)
        }

    @Test
    fun `approve is a no-op when the bot already approved`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = BOT_APPROVED))

            service(server).approve("o/r", "7")

            assertEquals(listOf("GET /user", "GET $PR/reviews"), calls(server))
        }

    @Test
    fun `unapprove dismisses the latest approval of the bot`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = BOT_APPROVED))

            service(server).unapprove("o/r", "7")

            assertEquals("PUT $PR/reviews/5/dismissals", calls(server).last())
        }

    @Test
    fun `fetchFileBlame maps the commits that touched the file`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            val blame = service(server).fetchFileBlame("o/r", "src/a.kt", "main")

            assertEquals(listOf("c1"), blame.map { it.commit.sha })
            val request = server.requests().single()
            assertEquals("/repos/o/r/commits", request.url.encodedPath)
            assertEquals("src/a.kt", request.url.parameters["path"])
            assertEquals("main", request.url.parameters["sha"])
        }

    @Test
    fun `fetchApprovalSignals maps issue comments and reactions`() =
        runTest {
            val server = RecordingMockServer(
                listOf(
                    route(
                        HttpMethod.Get,
                        "/repos/o/r/issues/7/comments",
                        """[{"id":1,"body":"LGTM","user":{"login":"bob","id":3},"created_at":"2026-10-20T10:00:00Z"}]""",
                    ),
                    route(
                        HttpMethod.Get,
                        "/repos/o/r/issues/7/reactions",
                        """[{"content":"+1","user":{"login":"carol","id":4},"created_at":"2026-10-21T10:00:00Z"}]""",
                    ),
                ),
            )

            val signals = service(server).fetchApprovalSignals("o/r", "7")

            assertEquals(
                listOf(
                    ApprovalSignal(User("bob", "bob"), ApprovalMethod.COMMENT_KEYWORD, "LGTM", "2026-10-20T10:00:00Z"),
                    ApprovalSignal(User("carol", "carol"), ApprovalMethod.EMOJI_REACTION, "+1", "2026-10-21T10:00:00Z"),
                ),
                signals,
            )
        }

    private suspend fun service(server: RecordingMockServer) =
        GitHubPlatformService(server.engine).also {
            it.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t")))))
        }

    private suspend fun calls(server: RecordingMockServer) = server.requests().map { "${it.method.value} ${it.url.encodedPath}" }

    private fun routes(reviews: String): List<MockRoute> =
        listOf(
            route(HttpMethod.Get, "/user", """{"login":"bot","id":1}"""),
            route(HttpMethod.Get, "$PR/reviews", reviews),
            route(HttpMethod.Post, "$PR/reviews", """{"id":6}"""),
            route(HttpMethod.Put, "$PR/reviews/5/dismissals", """{"id":5}"""),
            route(
                HttpMethod.Get,
                "/repos/o/r/commits",
                """[{"sha":"c1","commit":{"message":"m","author":{"name":"Dev","email":"d@x","date":"2026-10-01T10:00:00Z"}}}]""",
            ),
        )

    private companion object {
        const val PR = "/repos/o/r/pulls/7"
        const val BOT_APPROVED =
            """[{"id":4,"user":{"login":"dev","id":2},"state":"APPROVED"},{"id":5,"user":{"login":"bot","id":1},"state":"APPROVED"}]"""
    }
}
