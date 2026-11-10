package io.karandash.platforms.azuredevops

import io.karandash.api.exception.ProviderApiException
import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AzureDevOpsVotesAndWritesTest {
    @Test
    fun `approve puts a vote of 10 when the bot has not approved yet`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            service(server).approve(PROJECT, "7")

            assertEquals(listOf("PUT $PR/reviewers/me"), writes(server))
            assertEquals("""{"vote":10}""", (server.requests().last().body as TextContent).text)
        }

    @Test
    fun `approve is a no-op when the bot already approved`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 10))

            service(server).approve(PROJECT, "7")

            assertEquals(emptyList(), writes(server))
        }

    @Test
    fun `unapprove resets the vote of the bot to 0`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 10))

            service(server).unapprove(PROJECT, "7")

            assertEquals(listOf("PUT $PR/reviewers/me"), writes(server))
            assertEquals("""{"vote":0}""", (server.requests().last().body as TextContent).text)
        }

    @Test
    fun `approve fails when Azure DevOps rejects the vote`() =
        runTest {
            val server = RecordingMockServer(listOf(rejected(HttpMethod.Put)) + routes(vote = 0))

            assertFailsWith<ProviderApiException> { service(server).approve(PROJECT, "7") }
        }

    @Test
    fun `unapprove fails when Azure DevOps rejects the vote`() =
        runTest {
            val server = RecordingMockServer(listOf(rejected(HttpMethod.Put)) + routes(vote = 10))

            assertFailsWith<ProviderApiException> { service(server).unapprove(PROJECT, "7") }
        }

    @Test
    fun `updateComment fails when Azure DevOps rejects the edit`() =
        runTest {
            val server = RecordingMockServer(listOf(rejected(HttpMethod.Patch)) + routes(vote = 0))

            assertFailsWith<ProviderApiException> { service(server).updateComment(PROJECT, "7", "21:1", "v2") }
        }

    @Test
    fun `resolveMarkedInlineComments fails when Azure DevOps rejects the status change`() =
        runTest {
            val server = RecordingMockServer(listOf(rejected(HttpMethod.Patch)) + routes(vote = 0))

            assertFailsWith<ProviderApiException> { service(server).resolveMarkedInlineComments(PROJECT, "7", MARKER) }
        }

    @Test
    fun `resolveMarkedInlineComments marks open marked threads fixed next to a file-level thread`() =
        runTest {
            val server = RecordingMockServer(routes(vote = 0))

            assertEquals(1, service(server).resolveMarkedInlineComments(PROJECT, "7", MARKER))
            assertEquals(listOf("PATCH $PR/threads/21"), writes(server))
        }

    @Test
    fun `addLabels posts each label and fails when Azure DevOps rejects one`() =
        runTest {
            val ok = RecordingMockServer(routes(vote = 0))
            service(ok).addLabels(PROJECT, "7", listOf("bug", "docs"))
            assertEquals(listOf("POST $PR/labels", "POST $PR/labels"), writes(ok))

            val rejecting = RecordingMockServer(listOf(rejected(HttpMethod.Post)) + routes(vote = 0))
            assertFailsWith<ProviderApiException> { service(rejecting).addLabels(PROJECT, "7", listOf("bug")) }
        }

    @Test
    fun `removeLabels deletes the labels present and fails when Azure DevOps rejects one`() =
        runTest {
            val ok = RecordingMockServer(routes(vote = 0))
            service(ok).removeLabels(PROJECT, "7", listOf("bug", "absent"))
            assertEquals(listOf("DELETE $PR/labels/l1"), writes(ok))

            val rejecting = RecordingMockServer(listOf(rejected(HttpMethod.Delete)) + routes(vote = 0))
            assertFailsWith<ProviderApiException> { service(rejecting).removeLabels(PROJECT, "7", listOf("bug")) }
        }

    private suspend fun service(server: RecordingMockServer) =
        AzureDevOpsPlatformService(server.engine).also {
            it.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t")))))
        }

    private suspend fun writes(server: RecordingMockServer) =
        server.requests().filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }

    private fun rejected(method: HttpMethod) =
        MockRoute({ it.method == method }, """{"message":"denied"}""", status = HttpStatusCode.Forbidden)

    private fun routes(vote: Int): List<MockRoute> =
        listOf(
            route(HttpMethod.Get, "$PR/reviewers/me", """{"vote":$vote}"""),
            route(
                HttpMethod.Get,
                "$PR/threads",
                """{"value":[{"id":21,"status":"active","comments":[{"id":1,"content":"$MARKER x"}],""" +
                    """"threadContext":{"filePath":"/a.kt","rightFileStart":{"line":2,"offset":1},""" +
                    """"rightFileEnd":{"line":2,"offset":1}}},""" +
                    // A file-level thread carries no line positions.
                    """{"id":22,"status":"active","comments":[{"id":2,"content":"human"}],""" +
                    """"threadContext":{"filePath":"/b.kt"}}],"count":2}""",
            ),
            route(
                HttpMethod.Get,
                PR,
                """{"pullRequestId":7,"title":"t","status":"active","labels":[{"id":"l1","name":"bug"}]}""",
            ),
            MockRoute({ it.method != HttpMethod.Get }, "{}"),
        )

    private companion object {
        const val PROJECT = "org/proj/demo"
        const val PR = "/org/proj/_apis/git/repositories/demo/pullrequests/7"
        const val MARKER = "<!-- k -->"
    }
}
