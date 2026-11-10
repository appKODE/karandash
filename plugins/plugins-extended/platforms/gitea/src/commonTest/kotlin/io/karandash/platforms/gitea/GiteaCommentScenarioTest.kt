package io.karandash.platforms.gitea

import io.karandash.models.DiffRefs
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One review run over the engine seam: fetch the PR, post an inline comment, then post and edit the summary. */
class GiteaCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary hit the expected endpoints with a token header`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = GiteaPlatformService(server.engine)
            val config = JsonObject(mapOf("url" to JsonPrimitive(URL), "token" to JsonPrimitive("t")))
            assertTrue(service.initialize(TestPluginContext(config)))

            val mr = service.fetchMergeRequest("o/demo", "7")
            val inlineId = service.postInlineComment("o/demo", "7", "inline", "a.kt", 2, DiffRefs("b", HEAD, "b"))
            val inlineEdit = service.updateNote("o/demo", "7", inlineId, inlineId, "inline v2")
            val summaryId = service.findExistingComment("o/demo", "7", MARKER) ?: service.postComment("o/demo", "7", "$MARKER v1")
            service.updateComment("o/demo", "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("11", inlineId)
            assertTrue(inlineEdit.isSuccess)
            val calls = server.requests().map { "${it.method.value} ${it.url.encodedPath}" }
            assertEquals(
                listOf(
                    "GET $REPO/commits/$HEAD/status",
                    "GET $PR",
                    "GET $PR.diff",
                    "GET $PR/commits",
                    "GET $PR/files",
                    "GET $PR/reviews",
                ),
                calls.take(FETCH_CALLS).sorted(),
            )
            assertEquals(
                listOf(
                    "POST $PR/reviews",
                    "PATCH $REPO/issues/comments/11",
                    "GET $ISSUE/comments",
                    "POST $ISSUE/comments",
                    "PATCH $REPO/issues/comments/21",
                ),
                calls.drop(FETCH_CALLS),
            )
            assertTrue(server.requests().all { it.headers["Authorization"] == "token t" })
        }

    @Test
    fun `the summary state reads back from the newest marked comment`() =
        runTest {
            val server = RecordingMockServer(listOf(route(HttpMethod.Get, "$ISSUE/comments", MARKED_COMMENTS)) + routes())
            val service = GiteaPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("url" to JsonPrimitive(URL), "token" to JsonPrimitive("t"))))))

            assertEquals("$MARKER new", service.findCommentBody("o/demo", "7", MARKER))
            assertNull(service.findCommentBody("o/demo", "7", "<!-- absent -->"))
        }

    private fun routes() =
        listOf(
            route(HttpMethod.Get, "$PR.diff", "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1,2 @@\n a\n+b\n"),
            route(HttpMethod.Get, "$PR/files", """[{"filename":"a.kt","status":"changed","additions":1,"deletions":0,"changes":1}]"""),
            route(HttpMethod.Get, "$PR/commits", "[]"),
            route(HttpMethod.Get, "$PR/reviews", "[]"),
            route(HttpMethod.Get, "/status", """{"state":"success","statuses":[]}"""),
            route(
                HttpMethod.Get,
                PR,
                """{"id":70,"number":7,"title":"Add key","body":"","html_url":"$URL/o/demo/pulls/7","state":"open",""" +
                    """"user":{"id":1,"login":"dev"},"head":{"ref":"f","sha":"$HEAD"},"base":{"ref":"main","sha":"b"},""" +
                    """"labels":[],"draft":false,"merged":false,"created_at":"$DATE","updated_at":"$DATE"}""",
            ),
            route(HttpMethod.Post, "$PR/reviews", """{"id":11,"state":"COMMENT"}"""),
            route(HttpMethod.Get, "$ISSUE/comments", "[]"),
            route(HttpMethod.Post, "$ISSUE/comments", """{"id":21,"body":"v1"}"""),
            route(HttpMethod.Patch, "$REPO/issues/comments/11", """{"id":11,"body":"inline v2"}"""),
            route(HttpMethod.Patch, "$REPO/issues/comments/21", """{"id":21,"body":"v2"}"""),
        )

    private companion object {
        const val MARKER = "<!-- s -->"
        const val MARKED_COMMENTS = """[{"id":1,"body":"$MARKER old"},{"id":2,"body":"plain"},{"id":3,"body":"$MARKER new"}]"""
        const val URL = "https://gitea.example.com"
        const val REPO = "/api/v1/repos/o/demo"
        const val PR = "$REPO/pulls/7"
        const val ISSUE = "$REPO/issues/7"
        const val HEAD = "h1"
        const val DATE = "2026-10-01T10:00:00Z"
        const val FETCH_CALLS = 6
    }
}
