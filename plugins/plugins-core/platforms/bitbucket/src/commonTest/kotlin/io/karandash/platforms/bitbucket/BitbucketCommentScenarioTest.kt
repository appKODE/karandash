package io.karandash.platforms.bitbucket

import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One review run over the engine seam: fetch the PR, post an inline comment, then post and edit the summary. */
class BitbucketCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary hit the expected endpoints with a bearer token`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = BitbucketPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            val mr = service.fetchMergeRequest("ws/demo", "7")
            val inlineId = service.postInlineComment("ws/demo", "7", "inline", "a.kt", 2, DiffRefs("b", "h", "b"))
            val inlineEdit = service.updateNote("ws/demo", "7", inlineId, inlineId, "inline v2")
            val summaryId = service.findExistingComment("ws/demo", "7", MARKER) ?: service.postComment("ws/demo", "7", "$MARKER v1")
            service.updateComment("ws/demo", "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("11", inlineId)
            assertIs<UnsupportedPlatformOperationException>(inlineEdit.exceptionOrNull())
            val calls = server.requests().map { "${it.method.value} ${it.url.encodedPath}" }
            assertEquals(
                listOf("GET $PR", "GET $PR/commits", "GET $PR/diff", "GET $PR/diffstat", "GET $PR/statuses"),
                calls.take(FETCH_CALLS).sorted(),
            )
            assertEquals(
                listOf("POST $PR/comments", "GET $PR/comments", "POST $PR/comments", "PUT $PR/comments/21"),
                calls.drop(FETCH_CALLS),
            )
            assertTrue(server.requests().all { it.headers["Authorization"] == "Bearer t" })
        }

    @Test
    fun `a multi-line inline comment sends start_to`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = BitbucketPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            service.postInlineComment("ws/demo", "7", "inline", "a.kt", 3, DiffRefs("b", "h", "b"), startLine = 1)

            val body = (server.requests().single().body as TextContent).text
            assertTrue(""""inline":{"path":"a.kt","to":3,"start_to":1}""" in body, body)
        }

    @Test
    fun `reviewers are resolved among workspace members and written as the full list`() =
        runTest {
            val members = """{"values":[{"user":{"uuid":"{u2}","display_name":"Ann","nickname":"ann","account_id":"557:a"}}]}"""
            val server =
                RecordingMockServer(
                    routes() + route(HttpMethod.Get, "/2.0/workspaces/ws/members", members) + route(HttpMethod.Put, PR, "{}")
                )
            val service = BitbucketPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            assertTrue(service.addReviewers("ws/demo", "7", listOf("ann", "{u3}")).isSuccess)

            val put = server.requests().single { it.method == HttpMethod.Put }
            assertEquals("""{"reviewers":[{"uuid":"{u3}"},{"uuid":"{u2}"}]}""", (put.body as TextContent).text)
            assertTrue(service.removeReviewers("ws/demo", "7", listOf("{u9}")).isSuccess, "removing a non-reviewer writes nothing")
            assertEquals(1, server.requests().count { it.method == HttpMethod.Put })
        }

    @Test
    fun `the summary state reads back from the newest marked comment`() =
        runTest {
            val server = RecordingMockServer(listOf(route(HttpMethod.Get, "$PR/comments", MARKED_COMMENTS)) + routes())
            val service = BitbucketPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            assertEquals("$MARKER new", service.findCommentBody("ws/demo", "7", MARKER))
            assertNull(service.findCommentBody("ws/demo", "7", "<!-- absent -->"))
        }

    private fun routes() =
        listOf(
            route(HttpMethod.Get, "$PR/diff", "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1,2 @@\n a\n+b\n"),
            route(
                HttpMethod.Get,
                "$PR/diffstat",
                """{"values":[{"status":"modified","lines_added":1,"lines_removed":0,""" +
                    """"old":{"path":"a.kt"},"new":{"path":"a.kt"}}]}""",
            ),
            route(HttpMethod.Get, "$PR/commits", """{"values":[]}"""),
            route(HttpMethod.Get, "$PR/statuses", """{"values":[]}"""),
            route(
                HttpMethod.Get,
                PR,
                """{"id":7,"title":"Add key","description":"","state":"OPEN","author":$USER,""" +
                    """"source":{"branch":{"name":"f"},"commit":{"hash":"h"},"repository":$REPO_JSON},""" +
                    """"destination":{"branch":{"name":"main"},"commit":{"hash":"b"},"repository":$REPO_JSON},""" +
                    """"created_on":"$DATE","updated_on":"$DATE","participants":[],"reviewers":[]}""",
            ),
            // An inline comment carries an `inline` position; the summary does not.
            MockRoute({ it.method == HttpMethod.Post && "\"inline\"" in (it.body as TextContent).text }, """{"id":11}"""),
            route(HttpMethod.Post, "$PR/comments", """{"id":21}"""),
            route(HttpMethod.Get, "$PR/comments", """{"values":[]}"""),
            route(HttpMethod.Put, "$PR/comments/21", """{"id":21}"""),
        )

    private companion object {
        const val MARKER = "<!-- s -->"
        const val MARKED_COMMENTS =
            """{"values":[{"id":1,"content":{"raw":"$MARKER old"}},{"id":2,"content":{"raw":"plain"}},""" +
                """{"id":3,"content":{"raw":"$MARKER new"}}]}"""
        const val PR = "/2.0/repositories/ws/demo/pullrequests/7"
        const val DATE = "2026-10-01T10:00:00Z"
        const val USER = """{"uuid":"{u1}","display_name":"Dev","nickname":"dev"}"""
        const val REPO_JSON = """{"uuid":"{r1}","name":"demo","full_name":"ws/demo"}"""
        const val FETCH_CALLS = 5
    }
}
