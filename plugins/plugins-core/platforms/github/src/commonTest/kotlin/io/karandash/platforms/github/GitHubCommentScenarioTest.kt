package io.karandash.platforms.github

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
import kotlin.test.assertTrue

/** One review run over the engine seam: fetch the PR, post an inline comment, then post and edit the summary. */
class GitHubCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary hit the expected endpoints with a bearer token`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = GitHubPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))

            val mr = service.fetchMergeRequest("o/r", "7")
            val inlineId = service.postInlineComment("o/r", "7", "inline", "a.kt", 2, DiffRefs("b", HEAD, "b"))
            val inlineEdit = service.updateNote("o/r", "7", inlineId, inlineId, "inline v2")
            val summaryId = service.findExistingComment("o/r", "7", MARKER) ?: service.postComment("o/r", "7", "$MARKER v1")
            service.updateComment("o/r", "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("11", inlineId)
            assertTrue(inlineEdit.isSuccess)
            val calls = server.requests().map { "${it.method.value} ${it.url.encodedPath}" }
            assertEquals(
                listOf(
                    "GET $COMMITS/$HEAD/check-runs",
                    "GET $COMMITS/$HEAD/status",
                    "GET $PR",
                    "GET $PR/commits",
                    "GET $PR/files",
                    "GET $PR/reviews",
                ),
                calls.take(FETCH_CALLS).sorted(),
            )
            assertEquals(
                listOf(
                    "POST $PR/comments",
                    "PATCH $REPO/pulls/comments/11",
                    "GET $ISSUE/comments",
                    "POST $ISSUE/comments",
                    "PATCH $REPO/issues/comments/21",
                ),
                calls.drop(FETCH_CALLS),
            )
            assertTrue(server.requests().all { it.headers["Authorization"] == "Bearer t" })
        }

    private fun routes() =
        listOf(
            route(HttpMethod.Get, "/check-runs", """{"total_count":0,"check_runs":[]}"""),
            route(HttpMethod.Get, "/status", """{"state":"success","statuses":[]}"""),
            route(HttpMethod.Get, "$PR/commits", "[]"),
            route(HttpMethod.Get, "$PR/reviews", "[]"),
            route(HttpMethod.Get, "$PR/files", """[{"filename":"a.kt","status":"modified","patch":"@@ -1 +1,2 @@\n a\n+b"}]"""),
            route(
                HttpMethod.Get,
                PR,
                """{"id":1,"number":7,"title":"Add key","body":null,"html_url":"https://github.com/o/r/pull/7","state":"open",""" +
                    """"draft":false,"user":$USER,"head":{"ref":"f","sha":"$HEAD","label":"o:f"},""" +
                    """"base":{"ref":"main","sha":"b","label":"o:main"},"created_at":"$DATE","updated_at":"$DATE"}""",
            ),
            route(HttpMethod.Post, "$PR/comments", """{"id":11,"body":"inline"}"""),
            route(HttpMethod.Patch, "$REPO/pulls/comments/11", """{"id":11,"body":"inline v2"}"""),
            route(HttpMethod.Get, "$ISSUE/comments", "[]"),
            route(HttpMethod.Post, "$ISSUE/comments", """{"id":21,"body":"v1"}"""),
            route(HttpMethod.Patch, "$REPO/issues/comments/21", """{"id":21,"body":"v2"}"""),
        )

    private companion object {
        const val REPO = "/repos/o/r"
        const val PR = "$REPO/pulls/7"
        const val ISSUE = "$REPO/issues/7"
        const val COMMITS = "$REPO/commits"
        const val HEAD = "h1"
        const val DATE = "2026-10-01T10:00:00Z"
        const val USER = """{"login":"dev","id":1}"""
        const val MARKER = "<!-- s -->"
        const val FETCH_CALLS = 6
    }
}
