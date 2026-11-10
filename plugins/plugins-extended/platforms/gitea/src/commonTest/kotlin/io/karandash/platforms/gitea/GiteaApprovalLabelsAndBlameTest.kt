package io.karandash.platforms.gitea

import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GiteaApprovalLabelsAndBlameTest {
    @Test
    fun `approve posts an APPROVED review when the bot has not approved yet`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            service(server).approve("o/demo", "7")

            assertEquals("POST $PR/reviews", writes(server).single())
            assertTrue("\"event\":\"APPROVED\"" in text(server.requests().last()))
        }

    @Test
    fun `approve is a no-op when the bot already approved`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = BOT_APPROVED))

            service(server).approve("o/demo", "7")

            assertEquals(emptyList(), writes(server))
        }

    @Test
    fun `unapprove dismisses the latest approval of the bot`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = BOT_APPROVED))

            service(server).unapprove("o/demo", "7")

            assertEquals(listOf("POST $PR/reviews/5/dismissals"), writes(server))
        }

    @Test
    fun `addLabels resolves names case-insensitively and passes numeric ids through`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            service(server).addLabels("o/demo", "7", listOf("BUG", "42", "missing"))

            assertEquals(listOf("POST $REPO/issues/7/labels"), writes(server))
            assertEquals("""{"labels":[1,42]}""", text(server.requests().last()))
        }

    @Test
    fun `removeLabels deletes each resolved label`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            service(server).removeLabels("o/demo", "7", listOf("bug", "docs"))

            assertEquals(listOf("DELETE $REPO/issues/7/labels/1", "DELETE $REPO/issues/7/labels/2"), writes(server))
        }

    @Test
    fun `setDraft patches the pull request draft flag`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            service(server).setDraft("o/demo", "7", true)

            assertEquals(listOf("PATCH $PR"), writes(server))
            assertEquals("""{"draft":true}""", text(server.requests().last()))
        }

    @Test
    fun `fetchFileBlame maps the commits that touched the file`() =
        runTest {
            val server = RecordingMockServer(routes(reviews = "[]"))

            val blame = service(server).fetchFileBlame("o/demo", "src/a.kt", "main")

            assertEquals(listOf("c1"), blame.map { it.commit.sha })
            val request = server.requests().single()
            assertEquals("$REPO/commits", request.url.encodedPath)
            assertEquals("src/a.kt", request.url.parameters["path"])
            assertEquals("main", request.url.parameters["sha"])
        }

    private suspend fun service(server: RecordingMockServer) =
        GiteaPlatformService(server.engine).also {
            val config = mapOf("url" to "https://gitea.example.com", "token" to "t").mapValues { e -> JsonPrimitive(e.value) }
            it.initialize(TestPluginContext(JsonObject(config)))
        }

    private suspend fun writes(server: RecordingMockServer) =
        server.requests().filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }

    private fun text(request: HttpRequestData) = (request.body as? TextContent)?.text.orEmpty()

    private fun routes(reviews: String): List<MockRoute> =
        listOf(
            route(HttpMethod.Get, "/api/v1/user", """{"id":1,"login":"bot"}"""),
            route(HttpMethod.Get, "$PR/reviews", reviews),
            route(HttpMethod.Post, "$PR/reviews", """{"id":6,"body":"","state":"APPROVED"}"""),
            route(HttpMethod.Post, "$PR/reviews/5/dismissals", "{}"),
            route(HttpMethod.Get, "$REPO/labels", """[{"id":1,"name":"bug"},{"id":2,"name":"docs"}]"""),
            route(HttpMethod.Post, "$REPO/issues/7/labels", "[]"),
            MockRoute({ it.method == HttpMethod.Delete }, ""),
            MockRoute({ it.method == HttpMethod.Patch }, "{}"),
            route(
                HttpMethod.Get,
                "$REPO/commits",
                """[{"sha":"c1","commit":{"message":"m","author":$AUTHOR,"committer":$AUTHOR}}]""",
            ),
        )

    private companion object {
        const val REPO = "/api/v1/repos/o/demo"
        const val PR = "$REPO/pulls/7"
        const val AUTHOR = """{"name":"Dev","email":"d@x","date":"2026-10-01T10:00:00Z"}"""
        const val BOT_APPROVED =
            """[{"id":4,"user":{"id":2,"login":"dev"},"state":"APPROVED"},{"id":5,"user":{"id":1,"login":"bot"},"state":"APPROVED"}]"""
    }
}
