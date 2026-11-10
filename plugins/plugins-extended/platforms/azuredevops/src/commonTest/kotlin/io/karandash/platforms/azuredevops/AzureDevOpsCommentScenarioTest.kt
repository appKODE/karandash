package io.karandash.platforms.azuredevops

import io.karandash.models.DiffRefs
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One review run over the engine seam: fetch the PR, post an inline thread, then post and edit the summary. */
class AzureDevOpsCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary hit the expected endpoints with basic auth`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = AzureDevOpsPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            val mr = service.fetchMergeRequest(PROJECT, "7")
            val inlineId = service.postInlineComment(PROJECT, "7", "inline", "a.kt", 2, DiffRefs(BASE, HEAD, BASE))
            val inlineEdit = service.updateNote(PROJECT, "7", inlineId, inlineId, "inline v2")
            val summaryId = service.findExistingComment(PROJECT, "7", MARKER) ?: service.postComment(PROJECT, "7", "$MARKER v1")
            service.updateComment(PROJECT, "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("11", inlineId)
            assertTrue(inlineEdit.isSuccess)
            // The provider client spells `pullRequests`, the comment client `pullrequests`; Azure DevOps ignores case.
            val calls = server.requests().map { "${it.method.value} ${it.url.encodedPath.lowercase()}" }
            assertEquals(
                listOf(
                    "GET $REPO/blobs/base-blob",
                    "GET $REPO/blobs/head-blob",
                    "GET $PR",
                    "GET $PR/commits",
                    "GET $PR/iterations",
                    "GET $PR/iterations/1/changes",
                    "GET $PR/statuses",
                ),
                calls.take(FETCH_CALLS).sorted(),
            )
            assertEquals(
                listOf(
                    "POST $PR/threads",
                    "PATCH $PR/threads/11/comments/11",
                    "GET $PR/threads",
                    "POST $PR/threads",
                    "PATCH $PR/threads/21/comments/1",
                ),
                calls.drop(FETCH_CALLS),
            )
            assertTrue(server.requests().all { it.headers["Authorization"] == "Basic OnQ=" })
        }

    @Test
    fun `a multi-line inline thread starts on its first line`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = AzureDevOpsPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            service.postInlineComment(PROJECT, "7", "inline", "a.kt", 3, DiffRefs(BASE, HEAD, BASE), startLine = 1)

            val body = (server.requests().last().body as TextContent).text
            assertTrue(""""rightFileStart":{"line":1,"offset":1}""" in body, body)
            assertTrue(""""rightFileEnd":{"line":3,"offset":1}""" in body, body)
        }

    @Test
    fun `reviewers are resolved via identities and skipped when already present`() =
        runTest {
            val guid = "11111111-2222-3333-4444-555555555555"
            val server = RecordingMockServer(
                listOf(
                    route(HttpMethod.Get, "/org/_apis/identities", """{"count":1,"value":[{"id":"ann-id"}]}"""),
                    route(HttpMethod.Get, "$PR/reviewers", """{"count":1,"value":[{"id":"$guid"}]}"""),
                    route(HttpMethod.Put, "$PR/reviewers/ann-id", "{}"),
                    route(HttpMethod.Delete, "$PR/reviewers/$guid", ""),
                ),
            )
            val service = AzureDevOpsPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            assertTrue(service.addReviewers(PROJECT, "7", listOf("ann@example.com", guid)).isSuccess)
            assertTrue(service.removeReviewers(PROJECT, "7", listOf(guid)).isSuccess)

            val writes = server.requests().filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }
            assertEquals(listOf("PUT $PR/reviewers/ann-id", "DELETE $PR/reviewers/$guid"), writes)
            val search = server.requests().first { it.url.encodedPath.endsWith("/identities") }.url
            assertEquals("vssps.dev.azure.com", search.host)
            assertEquals("ann@example.com", search.parameters["filterValue"])
        }

    @Test
    fun `the summary state reads back from the newest marked comment`() =
        runTest {
            val server = RecordingMockServer(listOf(route(HttpMethod.Get, "$PR/threads", MARKED_THREADS)) + routes())
            val service = AzureDevOpsPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            assertEquals("$MARKER new", service.findCommentBody("$PROJECT", "7", MARKER))
            assertNull(service.findCommentBody("$PROJECT", "7", "<!-- absent -->"))
        }

    private fun routes(): List<MockRoute> {
        fun get(
            suffix: String,
            body: String,
        ) = MockRoute({
            it.method == HttpMethod.Get && it.url.encodedPath
                .lowercase()
                .endsWith(suffix.lowercase())
        }, body)
        return listOf(
            MockRoute({ isContent(it, HEAD, "head-blob") }, "a\nb\n", TEXT),
            MockRoute({ isContent(it, BASE, "base-blob") }, "a\n", TEXT),
            get(
                "$PR/iterations/1/changes",
                """{"changeEntries":[{"changeTrackingId":1,"changeId":1,"changeType":"edit",""" +
                    """"item":{"objectId":"head-blob","originalObjectId":"base-blob","path":"/a.kt"}}]}""",
            ),
            get(
                "$PR/iterations",
                """{"count":1,"value":[{"id":1,"sourceRefCommit":{"commitId":"$HEAD"},""" +
                    """"targetRefCommit":{"commitId":"$BASE"},"commonRefCommit":{"commitId":"$BASE"}}]}""",
            ),
            get("$PR/commits", """{"count":0,"value":[]}"""),
            get("$PR/statuses", """{"count":0,"value":[]}"""),
            get(
                PR,
                """{"pullRequestId":7,"title":"Add key","description":"","status":"active",""" +
                    """"createdBy":{"id":"u1","displayName":"Dev","uniqueName":"dev@example.com"},""" +
                    """"creationDate":"$DATE","sourceRefName":"refs/heads/f","targetRefName":"refs/heads/main",""" +
                    """"isDraft":false,"reviewers":[],"labels":[],""" +
                    """"lastMergeSourceCommit":{"commitId":"$HEAD"},"lastMergeTargetCommit":{"commitId":"$BASE"}}""",
            ),
            // An inline thread carries a `threadContext`; the summary thread does not.
            MockRoute(
                { it.method == HttpMethod.Post && "threadContext" in (it.body as TextContent).text },
                """{"id":11,"comments":[{"id":1,"content":"inline"}]}""",
            ),
            route(HttpMethod.Post, "$PR/threads", """{"id":21,"comments":[{"id":1,"content":"v1"}]}"""),
            route(HttpMethod.Get, "$PR/threads", """{"count":0,"value":[]}"""),
            route(HttpMethod.Patch, "$PR/threads/11/comments/11", """{"id":11,"content":"inline v2"}"""),
            route(HttpMethod.Patch, "$PR/threads/21/comments/1", """{"id":1,"content":"v2"}"""),
        )
    }

    private fun isContent(
        request: HttpRequestData,
        sha: String,
        blobId: String,
    ): Boolean {
        val path = request.url.encodedPath
        return path.endsWith("/blobs/$blobId") ||
            (path.endsWith("/items") && request.url.parameters["versionDescriptor.version"] == sha)
    }

    private companion object {
        const val MARKER = "<!-- s -->"
        const val MARKED_THREADS =
            """{"count":3,"value":[{"id":1,"comments":[{"id":1,"content":"$MARKER old"}]},""" +
                """{"id":2,"comments":[{"id":1,"content":"$MARKER new"},{"id":2,"content":"plain"}]},""" +
                """{"id":3,"comments":[{"id":1,"content":"$MARKER status changed","commentType":"system"}]}]}"""
        const val PROJECT = "org/proj/demo"
        const val REPO = "/org/proj/_apis/git/repositories/demo"
        const val PR = "$REPO/pullrequests/7"
        const val HEAD = "h1"
        const val BASE = "b1"
        const val DATE = "2026-10-01T10:00:00Z"
        const val TEXT = "text/plain"
        const val FETCH_CALLS = 7
    }
}
