package io.karandash.platforms.gitlab

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

/** One review run over the engine seam: fetch the MR, post and edit an inline note, then post and edit the summary. */
class GitLabCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary hit the expected endpoints with a private token`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = GitLabPlatformService(server.engine)
            val config = JsonObject(mapOf("url" to JsonPrimitive(URL), "token" to JsonPrimitive("t")))
            assertTrue(service.initialize(TestPluginContext(config)))

            val mr = service.fetchMergeRequest("g/p", "7")
            val threadId = service.postInlineComment("g/p", "7", "inline", "a.kt", 2, DiffRefs("b", "h", "b"))
            service.updateNote("g/p", "7", threadId, "11", "inline v2").getOrThrow()
            val summaryId = service.findExistingComment("g/p", "7", MARKER) ?: service.postComment("g/p", "7", "$MARKER v1")
            service.updateComment("g/p", "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("d1", threadId)
            val calls = server.requests().map { "${it.method.value} ${it.url.encodedPath}" }
            assertEquals(
                listOf("GET $MR", "GET $MR/approvals", "GET $MR/changes", "GET $MR/commits"),
                calls.take(FETCH_CALLS).sorted(),
            )
            assertEquals(
                listOf(
                    "POST $MR/discussions",
                    "PUT $MR/discussions/d1/notes/11",
                    "GET $MR/notes",
                    "POST $MR/notes",
                    "PUT $MR/notes/21",
                ),
                calls.drop(FETCH_CALLS),
            )
            assertTrue(server.requests().all { it.headers["PRIVATE-TOKEN"] == "t" && it.headers["Authorization"] == null })
        }

    private fun routes() =
        listOf(
            route(HttpMethod.Get, "$MR/commits", "[]"),
            route(HttpMethod.Get, "$MR/approvals", """{"approved_by":[]}"""),
            route(
                HttpMethod.Get,
                "$MR/changes",
                """{"changes":[{"old_path":"a.kt","new_path":"a.kt","new_file":false,"renamed_file":false,""" +
                    """"deleted_file":false,"diff":"@@ -1 +1,2 @@\n a\n+b\n"}]}""",
            ),
            route(
                HttpMethod.Get,
                MR,
                """{"id":1,"iid":7,"title":"Add key","description":null,"source_branch":"f","target_branch":"main",""" +
                    """"author":$USER,"state":"opened"}""",
            ),
            route(HttpMethod.Post, "$MR/discussions", """{"id":"d1","notes":[${note(11, "inline")}]}"""),
            route(HttpMethod.Put, "$MR/discussions/d1/notes/11", note(11, "inline v2")),
            route(HttpMethod.Get, "$MR/notes", "[]"),
            route(HttpMethod.Post, "$MR/notes", note(21, "v1")),
            route(HttpMethod.Put, "$MR/notes/21", note(21, "v2")),
        )

    private fun note(
        id: Int,
        body: String,
    ) = """{"id":$id,"body":"$body","author":$USER,"created_at":"2026-10-01T10:00:00Z"}"""

    private companion object {
        const val URL = "https://gitlab.example.com"
        const val MR = "/api/v4/projects/g%2Fp/merge_requests/7"
        const val USER = """{"id":1,"username":"bot","name":"bot"}"""
        const val FETCH_CALLS = 4
        const val MARKER = "<!-- s -->"
    }
}
