package io.karandash.platforms.gerrit

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
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GerritVotesAndEditsTest {
    @Test
    fun `approve sets Code-Review 2 when the bot has not voted yet`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            service(server).approve("demo", "7")

            assertEquals(listOf("POST $REVIEW"), writes(server))
            assertTrue(""""labels":{"Code-Review":2}""" in text(server.requests().last()))
        }

    @Test
    fun `approve is a no-op when the bot already voted 2`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 2))

            service(server).approve("demo", "7")

            assertEquals(emptyList(), writes(server))
        }

    @Test
    fun `unapprove resets the Code-Review vote of the bot to 0`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 2))

            service(server).unapprove("demo", "7")

            assertEquals(listOf("POST $REVIEW"), writes(server))
            assertTrue(""""labels":{"Code-Review":0}""" in text(server.requests().last()))
        }

    @Test
    fun `setDraft toggles work in progress`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            service(server).setDraft("demo", "7", true)
            service(server).setDraft("demo", "7", false)

            assertEquals(listOf("POST /a/changes/7/wip", "POST /a/changes/7/ready"), writes(server))
        }

    @Test
    fun `updateTitle replaces only the subject line of the commit message`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            service(server).updateTitle("demo", "7", "New subject")

            assertEquals(listOf("PUT /a/changes/7/message"), writes(server))
            assertEquals(
                """{"message":"New subject\n\nBody\n\nChange-Id: I1\n"}""",
                text(server.requests().last()),
            )
        }

    @Test
    fun `updateDescription keeps the subject and the Change-Id footer`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            service(server).updateDescription("demo", "7", "New body")

            assertEquals(
                """{"message":"Old subject\n\nNew body\n\nChange-Id: I1\n"}""",
                text(server.requests().last()),
            )
        }

    @Test
    fun `fetchMergeRequestByUrl reads a nested project name from the url`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            // No route answers the change, so the fetch fails; only the requested path matters here.
            assertFails { service(server).fetchMergeRequestByUrl("https://gerrit.example.com/c/team/demo/+/7") }

            assertEquals(
                "/a/changes/team%2Fdemo~7",
                server
                    .requests()
                    .first()
                    .url.encodedPath
            )
        }

    private suspend fun service(server: RecordingMockServer) =
        GerritPlatformService(server.engine).also {
            val config = mapOf("url" to "https://gerrit.example.com", "username" to "bot", "token" to "t")
            it.initialize(TestPluginContext(JsonObject(config.mapValues { e -> JsonPrimitive(e.value) })))
        }

    private suspend fun writes(server: RecordingMockServer) =
        server.requests().filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }

    private fun text(request: HttpRequestData) = (request.body as? TextContent)?.text.orEmpty()

    private fun routes(vote: Int): List<MockRoute> =
        listOf(
            route(HttpMethod.Get, "/a/accounts/self", """$XSSI{"_account_id":1000}"""),
            route(
                HttpMethod.Get,
                "/a/changes/demo~7",
                """$XSSI{"id":"demo~main~I1","project":"demo","change_id":"I1","subject":"Old subject","status":"NEW",""" +
                    """"owner":{"_account_id":1},"branch":"main",""" +
                    """"labels":{"Code-Review":{"all":[{"value":$vote,"_account_id":1000}]}}}""",
            ),
            route(
                HttpMethod.Get,
                "/a/changes/7/message",
                """$XSSI{"subject":"Old subject","full_message":"Old subject\n\nBody\n\nChange-Id: I1\n"}""",
            ),
            MockRoute({ it.method != HttpMethod.Get }, "$XSSI{}"),
        )

    private companion object {
        const val REVIEW = "/a/changes/7/revisions/current/review"
        const val XSSI = ")]}'\n"
    }
}
