package io.karandash.platforms.gitlab

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.finding.ReviewAward
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabReviewThreadsTest {
    private val mr = "/api/v4/projects/42/merge_requests/7"

    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Call(
        val method: HttpMethod,
        val path: String,
        val query: String,
        val body: String?,
    )

    /** Answers `"METHOD path"` from [routes] (status 200 unless the value is a pair), 404 for anything else. */
    private suspend fun service(
        calls: MutableList<Call>,
        routes: Map<String, Any>,
    ): GitLabPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += Call(request.method, path, request.url.encodedQuery, (request.body as? TextContent)?.text)
            val (status, body) = when (val route = routes["${request.method.value} $path"]) {
                null -> HttpStatusCode.NotFound to """{"message":"404 Not Found"}"""
                is Pair<*, *> -> route.first as HttpStatusCode to route.second as String
                else -> HttpStatusCode.OK to route as String
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitLabPlatformService()
        service.client.rebuild("test") { GitLabClient(token = "t", httpClient = http, responseCache = null) }
        return service
    }

    private fun user(name: String) = """{"id":${name.length},"username":"$name","name":"$name"}"""

    private fun member(
        name: String,
        accessLevel: Int = DEVELOPER,
    ) = """{"id":${name.length},"username":"$name","name":"$name","access_level":$accessLevel}"""

    private fun note(
        id: Int,
        body: String,
        resolvedBy: String? = null,
        system: Boolean = false,
    ): String {
        val resolver = resolvedBy?.let { user(it) } ?: "null"
        return """{"id":$id,"body":"$body","author":${user("bot")},"created_at":"2026-10-20T10:00:00Z",""" +
            """"system":$system,"resolvable":true,"resolved":${resolvedBy != null},"resolved_by":$resolver,""" +
            """"position":{"new_path":"a.kt","old_path":"a.kt","new_line":5,"old_line":null}}"""
    }

    private fun award(
        name: String,
        by: String,
    ) = """{"id":1,"name":"$name","user":${user(by)}}"""

    @Test
    fun `listReviewThreads maps every note with its position and award emoji`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(
                calls,
                mapOf(
                    "GET $mr/discussions" to """[{"id":"d1","notes":[${note(1, "hi")},${note(2, "moved", system = true)}]}]""",
                    "GET $mr/notes/1/award_emoji" to "[${award("thumbsdown", "alice")}]",
                ),
            )

            val threads = service.listReviewThreads("42", "7")

            val position = ReviewThreadPosition(newPath = "a.kt", oldPath = "a.kt", newLine = 5, oldLine = null)
            val first = ReviewNote(
                id = "1",
                body = "hi",
                author = "bot",
                createdAt = "2026-10-20T10:00:00Z",
                resolvable = true,
                awards = listOf(ReviewAward("thumbsdown", "alice")),
            )
            val second = first.copy(id = "2", body = "moved", system = true, awards = emptyList())
            assertEquals(listOf(ReviewThread(id = "d1", notes = listOf(first, second), position = position)), threads)
            // system notes carry no reactions worth a request
            assertTrue(calls.none { it.path.endsWith("/notes/2/award_emoji") })
        }

    @Test
    fun `listReviewThreads throws when the discussions call fails`() =
        runTest {
            val service = service(mutableListOf(), mapOf("GET $mr/discussions" to (HttpStatusCode.InternalServerError to "{}")))

            assertFailsWith<ProviderApiException> { service.listReviewThreads("42", "7") }
        }

    @Test
    fun `a thumbs-down by someone other than the author dismisses a marked note`() =
        runTest {
            val service = service(
                mutableListOf(),
                mapOf(
                    "GET $mr/discussions" to """[{"id":"d1","notes":[${note(1, "a <!-- kd -->")}]}]""",
                    "GET $mr/notes/1/award_emoji" to "[${award("thumbsdown", "alice")}]",
                ),
            )

            assertEquals(listOf("a <!-- kd -->"), service.listDismissedInlineComments("42", "7", "<!-- kd -->"))
        }

    @Test
    fun `fetchApprovalSignals maps user comments and reactions on the merge request`() =
        runTest {
            val service = service(
                mutableListOf(),
                mapOf(
                    "GET $mr/notes" to "[${note(1, "LGTM")},${note(2, "assigned", system = true)}]",
                    "GET $mr/award_emoji" to "[${award("thumbsup", "alice")}]",
                ),
            )

            val signals = service.fetchApprovalSignals("42", "7")

            assertEquals(
                listOf(
                    ApprovalSignal(User("bot", "bot"), ApprovalMethod.COMMENT_KEYWORD, "LGTM", "2026-10-20T10:00:00Z"),
                    ApprovalSignal(User("alice", "alice"), ApprovalMethod.EMOJI_REACTION, "thumbsup"),
                ),
                signals,
            )
        }

    @Test
    fun `fetchApprovalSignals throws when the reactions call fails`() =
        runTest {
            val service = service(
                mutableListOf(),
                mapOf("GET $mr/notes" to "[]", "GET $mr/award_emoji" to (HttpStatusCode.InternalServerError to "{}")),
            )

            assertFailsWith<ProviderApiException> { service.fetchApprovalSignals("42", "7") }
        }

    @Test
    fun `a failed award call on one note leaves the other notes evaluated`() =
        runTest {
            val service = service(
                mutableListOf(),
                mapOf(
                    "GET $mr/discussions" to """[{"id":"d1","notes":[${note(1, "a <!-- kd -->")},${note(2, "b <!-- kd -->")}]}]""",
                    "GET $mr/notes/1/award_emoji" to (HttpStatusCode.InternalServerError to "{}"),
                    "GET $mr/notes/2/award_emoji" to "[${award("thumbsdown", "alice")}]",
                ),
            )

            assertEquals(listOf("b <!-- kd -->"), service.listDismissedInlineComments("42", "7", "<!-- kd -->"))
        }

    @Test
    fun `a thumbs-down by the author or a thumbs-up does not dismiss a marked note`() =
        runTest {
            val service = service(
                mutableListOf(),
                mapOf(
                    "GET $mr/discussions" to """[{"id":"d1","notes":[${note(1, "a <!-- kd -->")}]}]""",
                    "GET $mr/notes/1/award_emoji" to "[${award("thumbsdown", "bot")},${award("thumbsup", "alice")}]",
                ),
            )

            assertEquals(emptyList(), service.listDismissedInlineComments("42", "7", "<!-- kd -->"))
        }

    @Test
    fun `resolveThread resolves first and then replies with the note`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(
                calls,
                mapOf("POST $mr/discussions/d1/notes" to """{"id":9,"body":"fixed"}""", "PUT $mr/discussions/d1" to "{}"),
            )

            assertTrue(service.resolveThread("42", "7", "d1", "fixed").isSuccess)

            assertEquals(listOf(HttpMethod.Put, HttpMethod.Post), calls.map { it.method })
            assertEquals("""{"resolved":true}""", calls[0].body)
            assertEquals("""{"body":"fixed"}""", calls[1].body)
        }

    @Test
    fun `resolveThread does not reply when the resolve fails`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(
                calls,
                mapOf(
                    "POST $mr/discussions/d1/notes" to """{"id":9,"body":"fixed"}""",
                    "PUT $mr/discussions/d1" to (HttpStatusCode.Forbidden to "{}"),
                ),
            )

            assertTrue(service.resolveThread("42", "7", "d1", "fixed").isFailure)
            assertEquals(listOf(HttpMethod.Put), calls.map { it.method })
        }

    @Test
    fun `resolveThread without a note only resolves and reports a failed resolve`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(calls, mapOf("PUT $mr/discussions/d1" to (HttpStatusCode.Forbidden to "{}")))

            val result = service.resolveThread("42", "7", "d1")

            assertIs<ProviderApiException>(result.exceptionOrNull())
            assertEquals(listOf("$mr/discussions/d1"), calls.map { it.path })
        }

    @Test
    fun `replyToThread adds a note and leaves the thread open`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(calls, mapOf("POST $mr/discussions/d1/notes" to """{"id":9,"body":"hi"}"""))

            assertTrue(service.replyToThread("42", "7", "d1", "hi").isSuccess)

            assertEquals(listOf(HttpMethod.Post), calls.map { it.method })
            assertEquals("""{"body":"hi"}""", calls.single().body)
            assertTrue(service.replyToThread("42", "7", "d2", "hi").isFailure)
        }

    @Test
    fun `isProjectMember matches the exact username among the query results`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(calls, mapOf("GET /api/v4/projects/42/members/all" to "[${member("alice2")},${member("alice")}]"))

            assertTrue(service.isProjectMember("42", "alice"))
            assertEquals(false, service.isProjectMember("42", "ali"))
            assertTrue("query=alice" in calls.first().query, calls.first().query)
        }

    @Test
    fun `currentUsername is the user the token posts as`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(calls, mapOf("GET /api/v4/user" to user("karandash-bot")))

            assertEquals("karandash-bot", service.currentUsername())
            assertEquals("/api/v4/user", calls.single().path)
        }

    @Test
    fun `isProjectMember needs Developer access`() =
        runTest {
            val members = "[${member("rita", REPORTER)},${member("dev", DEVELOPER)},${member("owner", 50)}]"
            val service = service(mutableListOf(), mapOf("GET /api/v4/projects/42/members/all" to members))

            assertEquals(false, service.isProjectMember("42", "rita"))
            assertTrue(service.isProjectMember("42", "dev"))
            assertTrue(service.isProjectMember("42", "owner"))
        }

    @Test
    fun `updateNote replaces the note body in place`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(calls, mapOf("PUT $mr/discussions/d1/notes/7" to """{"id":7,"body":"new"}"""))

            assertTrue(service.updateNote("42", "7", "d1", "7", "new").isSuccess)
            assertEquals("""{"body":"new"}""", calls.single().body)
        }

    @Test
    fun `updateNote fails on a missing note`() =
        runTest {
            val service = service(mutableListOf(), emptyMap())

            assertIs<ProviderApiException>(service.updateNote("42", "7", "d1", "7", "new").exceptionOrNull())
        }

    @Test
    fun `fetchFileContent reads the raw file at the ref`() =
        runTest {
            val calls = mutableListOf<Call>()
            val service = service(calls, mapOf("GET /api/v4/projects/42/repository/files/src%2Fa.kt/raw" to "line1\nline2\n"))

            assertEquals("line1\nline2\n", service.fetchFileContent("42", "abc123", "src/a.kt"))
            assertEquals("ref=abc123", calls.single().query)
        }

    @Test
    fun `fetchFileContent is null for a missing or oversized file`() =
        runTest {
            val big = "x".repeat(PlatformServiceModule.MAX_FILE_CONTENT_BYTES + 1)
            val service = service(mutableListOf(), mapOf("GET /api/v4/projects/42/repository/files/big.txt/raw" to big))

            assertNull(service.fetchFileContent("42", "abc123", "gone.kt"))
            assertNull(service.fetchFileContent("42", "abc123", "big.txt"))
        }

    /** Serves [body] for every request with [headers]; records each requested path. */
    private suspend fun rawService(
        paths: MutableList<String>,
        body: ByteArray,
        headers: Headers = headersOf(),
    ): GitLabPlatformService {
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond(ByteReadChannel(body), HttpStatusCode.OK, headers)
        }
        val service = GitLabPlatformService()
        service.client.rebuild("test") { GitLabClient(token = "t", httpClient = HttpClient(engine), responseCache = null) }
        return service
    }

    @Test
    fun `fetchFileContent skips a body whose Content-Length is over the cap without reading it`() =
        runTest {
            val declared = (PlatformServiceModule.MAX_FILE_CONTENT_BYTES + 1).toString()
            val service = rawService(mutableListOf(), "tiny".encodeToByteArray(), headersOf(HttpHeaders.ContentLength, declared))

            assertNull(service.fetchFileContent("42", "abc123", "big.txt"))
        }

    @Test
    fun `fetchFileContent keeps a file exactly at the cap`() =
        runTest {
            val body = "x".repeat(PlatformServiceModule.MAX_FILE_CONTENT_BYTES)
            val service = rawService(mutableListOf(), body.encodeToByteArray())

            assertEquals(body.length, service.fetchFileContent("42", "abc123", "edge.txt")?.length)
        }

    @Test
    fun `fetchFileContent is null for content with a NUL byte`() =
        runTest {
            val withNul = rawService(mutableListOf(), byteArrayOf(0x50, 0x4b, 0x00, 0x03))

            assertNull(withNul.fetchFileContent("42", "abc123", "a.png"))
        }

    @Test
    fun `fetchFileContent keeps non-UTF-8 text without a NUL byte`() =
        runTest {
            val cp1251 = byteArrayOf(0x61, 0xC3.toByte(), 0x28, 0xEF.toByte(), 0xF0.toByte())
            val service = rawService(mutableListOf(), cp1251)

            val text = service.fetchFileContent("42", "abc123", "legacy.txt")

            assertEquals(true, text?.startsWith("a"))
        }

    @Test
    fun `GitLab declares file content support`() {
        assertTrue(GitLabPlatformService().supportsFileContent)
    }

    @Test
    fun `fetchFileContent encodes a path-style project id and a file path with a space`() =
        runTest {
            val paths = mutableListOf<String>()
            val service = rawService(paths, "ok".encodeToByteArray())

            assertEquals("ok", service.fetchFileContent("group/proj", "abc123", "dir/my file.kt"))
            assertEquals(listOf("/api/v4/projects/group%2Fproj/repository/files/dir%2Fmy%20file.kt/raw"), paths)
        }
}

private const val REPORTER = 20
private const val DEVELOPER = 30
