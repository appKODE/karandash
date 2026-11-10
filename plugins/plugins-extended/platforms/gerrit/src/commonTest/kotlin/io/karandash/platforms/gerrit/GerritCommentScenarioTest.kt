package io.karandash.platforms.gerrit

import io.karandash.api.finding.InlineCommentData
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
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One review run over the engine seam: fetch the change, post an inline comment, then post and refresh the summary. */
class GerritCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary hit the expected endpoints with basic auth`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = GerritPlatformService(server.engine)
            val config = mapOf("url" to URL, "username" to "bot", "token" to "t").mapValues { JsonPrimitive(it.value) }
            assertTrue(service.initialize(TestPluginContext(JsonObject(config))))

            val mr = service.fetchMergeRequest("demo", "7")
            val inlineId = service.postInlineComment("demo", "7", "inline", "a.kt", 2, DiffRefs("b", HEAD, "b"))
            val inlineEdit = service.updateNote("demo", "7", inlineId, inlineId, "inline v2")
            val summaryId = service.findExistingComment("demo", "7", MARKER) ?: service.postComment("demo", "7", "$MARKER v1")
            // Change messages cannot be edited: an update whose body is not there yet posts a new review message.
            service.updateComment("demo", "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("7", inlineId)
            assertIs<UnsupportedPlatformOperationException>(inlineEdit.exceptionOrNull())
            assertEquals(
                listOf(
                    "GET /a/changes/demo~7",
                    "GET /a/changes/demo~7/revisions/$HEAD/patch",
                    "POST /a/changes/7/revisions/$HEAD/review",
                    "GET /a/changes/7",
                    "POST $REVIEW",
                    "GET /a/changes/7",
                    "POST $REVIEW",
                ),
                server.requests().map { "${it.method.value} ${it.url.encodedPath}" },
            )
            assertTrue(server.requests().all { it.headers["Authorization"] == "Basic Ym90OnQ=" })
        }

    @Test
    fun `a multi-line inline comment carries a range ending on its line`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = GerritPlatformService(server.engine)
            val config = mapOf("url" to URL, "username" to "bot", "token" to "t").mapValues { JsonPrimitive(it.value) }
            assertTrue(service.initialize(TestPluginContext(JsonObject(config))))

            service.postInlineComment("demo", "7", "inline", "a.kt", 3, DiffRefs("b", HEAD, "b"), startLine = 1)

            val body = (server.requests().single().body as TextContent).text
            assertTrue(""""range":{"start_line":1,"start_character":0,"end_line":3,"end_character":0}""" in body, body)
        }

    @Test
    fun `a batch of inline comments is one review grouped by file`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = GerritPlatformService(server.engine)
            val config = mapOf("url" to URL, "username" to "bot", "token" to "t").mapValues { JsonPrimitive(it.value) }
            assertTrue(service.initialize(TestPluginContext(JsonObject(config))))
            val comments =
                listOf(
                    InlineCommentData("a.kt", 1, body = "x"),
                    InlineCommentData("b.kt", 2, body = "y"),
                    InlineCommentData("a.kt", 3, body = "z")
                )

            assertEquals("7", service.postInlineComments("demo", "7", comments, DiffRefs("b", HEAD, "b")))

            val request = server.requests().single()
            assertTrue(request.url.encodedPath.endsWith("/a/changes/7/revisions/$HEAD/review"), request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue(Regex("\"a\\.kt\":\\[\\{\"line\":1,\"message\":\"x\".*\\{\"line\":3,\"message\":\"z\"").containsMatchIn(body), body)
            assertTrue(""""b.kt":[{"line":2,"message":"y"""" in body, body)
        }

    @Test
    fun `a marked thread someone else resolved is dismissed and the bot own resolve is not`() {
        fun c(
            id: String,
            author: Long,
            reply: String? = null,
            unresolved: Boolean? = null,
            at: String = "2026-10-01 10:00:00",
        ) = GerritPublishedComment(
            id,
            "$MARKER $id",
            inReplyTo = reply,
            author = GerritAccountInfo(author),
            unresolved = unresolved,
            updated = at
        )
        val comments = listOf(
            c("a", BOT),
            c("a1", HUMAN, reply = "a", unresolved = false, at = "2026-10-02 10:00:00"),
            c("b", BOT),
            c("b1", BOT, reply = "b", unresolved = false, at = "2026-10-02 10:00:00"),
            c("d", BOT),
            c("d1", HUMAN, reply = "d", unresolved = false, at = "2026-10-02 10:00:00"),
            c("d2", BOT, reply = "d1", unresolved = true, at = "2026-10-03 10:00:00"),
        )

        assertEquals(listOf("$MARKER a"), dismissedMarkedComments(comments, MARKER))
    }

    @Test
    fun `reviewers are added one by one and removed by account`() =
        runTest {
            val server =
                RecordingMockServer(
                    routes() + route(HttpMethod.Post, "/changes/7/reviewers", "$XSSI{}") + route(HttpMethod.Delete, "/reviewers/ann", "")
                )
            val service = GerritPlatformService(server.engine)
            val config = mapOf("url" to URL, "username" to "bot", "token" to "t").mapValues { JsonPrimitive(it.value) }
            assertTrue(service.initialize(TestPluginContext(JsonObject(config))))

            assertTrue(service.addReviewers("demo", "7", listOf("ann", "bob@example.com")).isSuccess)
            assertTrue(service.removeReviewers("demo", "7", listOf("ann")).isSuccess)

            val bodies = server.requests().filter { it.method == HttpMethod.Post }.map { (it.body as TextContent).text }
            assertEquals(listOf("""{"reviewer":"ann"}""", """{"reviewer":"bob@example.com"}"""), bodies)
            assertEquals(
                "/a/changes/7/reviewers/ann",
                server
                    .requests()
                    .single { it.method == HttpMethod.Delete }
                    .url.encodedPath
            )
        }

    @Test
    fun `the summary state reads back from the newest marked comment`() =
        runTest {
            val server = RecordingMockServer(listOf(route(HttpMethod.Get, "/changes/7", XSSI + MARKED_MESSAGES)) + routes())
            val service = GerritPlatformService(server.engine)
            assertTrue(
                service.initialize(
                    TestPluginContext(
                        JsonObject(
                            mapOf("url" to URL, "username" to "bot", "token" to "t").mapValues {
                                JsonPrimitive(it.value)
                            }
                        )
                    )
                )
            )

            assertEquals("$MARKER new", service.findCommentBody("demo", "7", MARKER))
            assertNull(service.findCommentBody("demo", "7", "<!-- absent -->"))
        }

    private fun routes() =
        listOf(
            route(HttpMethod.Get, "/revisions/$HEAD/patch", Base64.encode(DIFF.encodeToByteArray())),
            route(HttpMethod.Get, "/changes/demo~7", XSSI + CHANGE),
            route(HttpMethod.Get, "/changes/7", XSSI + MESSAGES),
            MockRoute({ it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/review") }, "$XSSI{}"),
        )

    private companion object {
        const val URL = "https://gerrit.example.com"
        const val REVIEW = "/a/changes/7/revisions/current/review"
        const val HEAD = "h1"
        const val BOT = 1L
        const val HUMAN = 2L
        const val XSSI = ")]}'\n"
        const val MARKER = "<!-- s -->"
        const val DIFF = "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1,2 @@\n a\n+b\n"
        const val DATE = "2026-10-01 10:00:00.000000000"
        const val OWNER = """{"_account_id":1000,"name":"Dev","username":"dev"}"""
        const val CHANGE =
            """{"id":"demo~main~I1","_number":7,"change_id":"I1","subject":"Add key","project":"demo",""" +
                """"branch":"main","status":"NEW","owner":$OWNER,"current_revision":"$HEAD",""" +
                """"revisions":{"$HEAD":{"kind":"REWORK","_number":1,"created":"$DATE","uploader":{"_account_id":1000},""" +
                """"ref":"refs/changes/07/7/1","commit":{"commit":"$HEAD","parents":[{"commit":"b","subject":"init"}],""" +
                """"author":{"name":"Dev","email":"dev@example.com","date":"$DATE"},""" +
                """"committer":{"name":"Dev","email":"dev@example.com","date":"$DATE"},""" +
                """"subject":"Add key","message":"Add key"},""" +
                """"files":{"a.kt":{"lines_inserted":1,"lines_deleted":0,"size_delta":2,"size":4}}}}}"""
        const val MESSAGES =
            """{"id":"demo~main~I1","project":"demo","change_id":"I1","subject":"Add key","status":"NEW",""" +
                """"owner":$OWNER,"branch":"main","messages":[{"id":"m1","message":"Uploaded patch set 1."}]}"""
        const val MARKED_MESSAGES =
            """{"id":"demo~main~I1","project":"demo","change_id":"I1","subject":"Add key","status":"NEW",""" +
                """"owner":$OWNER,"branch":"main","messages":[{"id":"m1","message":"Patch Set 1:\n\n$MARKER old"},""" +
                """{"id":"m2","message":"plain"},{"id":"m3","message":"Patch Set 1:\n\n$MARKER new"}]}"""
    }
}
