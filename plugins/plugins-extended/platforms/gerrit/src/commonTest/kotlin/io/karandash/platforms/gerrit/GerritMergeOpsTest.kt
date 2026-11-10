package io.karandash.platforms.gerrit

import io.karandash.api.platform.PlatformOperation
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GerritMergeOpsTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: String?,
    )

    private suspend fun serviceWith(
        captured: MutableList<Captured>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("{}", HttpStatusCode.OK) },
    ): GerritPlatformService {
        val engine = MockEngine { request ->
            captured += Captured(request.method, request.url.encodedPath, (request.body as? TextContent)?.text)
            handler(request)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GerritPlatformService()
        service.commentClient.rebuild("test") {
            GerritClient("https://gerrit.test", token = "pw", httpClient = http, username = "bot")
        }
        return service
    }

    @Test
    fun `merge close and reopen post submit abandon and restore`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            assertTrue(service.mergeMr("proj", "7").isSuccess)
            assertTrue(service.closeMr("proj", "7").isSuccess)
            assertTrue(service.reopenMr("proj", "7").isSuccess)

            assertEquals(
                listOf("/a/changes/7/submit", "/a/changes/7/abandon", "/a/changes/7/restore"),
                captured.map { it.path },
            )
            assertTrue(captured.all { it.method == HttpMethod.Post })
        }

    @Test
    fun `mergeMr returns failure when Gerrit refuses the submit`() =
        runTest {
            val service = serviceWith(mutableListOf()) { respond("blocked by Verified", HttpStatusCode.Conflict) }

            assertFalse(service.mergeMr("proj", "7").isSuccess)
        }

    @Test
    fun `labels are sent as hashtags unchanged in one call`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            service.addLabels("proj", "7", listOf("Needs Review", "bug"))
            service.removeLabels("proj", "7", listOf("Needs Review"))

            assertEquals(listOf("/a/changes/7/hashtags", "/a/changes/7/hashtags"), captured.map { it.path })
            assertEquals("""{"add":["Needs Review","bug"],"remove":[]}""", captured[0].body)
            assertEquals("""{"add":[],"remove":["Needs Review"]}""", captured[1].body)
        }

    @Test
    fun `resolveMarkedInlineCommentsForFiles replies resolved to open marked threads on re-reviewed files`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured) { inlineThreads(it) }

            assertEquals(1, service.resolveMarkedInlineCommentsForFiles("proj", "7", MARKER, setOf("a.kt")))

            assertEquals(listOf("/a/changes/7/comments", "/a/changes/7/revisions/2/review"), captured.map { it.path })
            val reply = checkNotNull(captured[1].body)
            assertTrue(""""in_reply_to":"c1"""" in reply && """"unresolved":false""" in reply && """"a.kt"""" in reply, reply)
        }

    @Test
    fun `resolveMarkedInlineComments resolves every open marked thread once per patch set and deletes nothing`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured) { inlineThreads(it) }

            assertEquals(2, service.resolveMarkedInlineComments("proj", "7", MARKER))

            assertEquals(
                listOf("/a/changes/7/comments", "/a/changes/7/revisions/2/review", "/a/changes/7/revisions/3/review"),
                captured.map { it.path },
            )
            assertFalse(captured.any { it.path.endsWith("/delete") })
        }

    @Test
    fun `resolveThread replies resolved on the patch set of the thread`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured) { inlineThreads(it) }

            assertTrue(service.resolveThread("proj", "7", "c3", "fixed").isSuccess)
            assertFalse(service.resolveThread("proj", "7", "nope", null).isSuccess)

            assertEquals("/a/changes/7/revisions/3/review", captured[1].path)
            val reply = checkNotNull(captured[1].body)
            assertTrue(""""in_reply_to":"c3"""" in reply && """"unresolved":false""" in reply && "fixed" in reply, reply)
        }

    @Test
    fun `findExistingComment picks the newest summary and skips a reply that quotes the marker`() =
        runTest {
            val messages = """)]}'
                {"id": "p~7", "project": "p", "change_id": "I7", "subject": "s", "status": "NEW", "branch": "main",
                 "owner": {"_account_id": 1}, "messages": [
                   {"id": "m1", "message": "Patch Set 1:\n\n$MARKER\nv1"},
                   {"id": "m2", "message": "Patch Set 2: Code-Review+1\n\n$MARKER\nv2"},
                   {"id": "m3", "message": "Patch Set 2:\n\n> $MARKER\nwhy is this flagged"}]}"""
            val service = serviceWith(mutableListOf()) { respond(messages, HttpStatusCode.OK) }

            assertEquals("m2", service.findExistingComment("proj", "7", MARKER))
        }

    @Test
    fun `updateComment does not repost a summary Gerrit already shows`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val messages = """)]}'
                {"id": "p~7", "project": "p", "change_id": "I7", "subject": "s", "status": "NEW", "branch": "main",
                 "owner": {"_account_id": 1}, "messages": [{"id": "m1", "message": "Patch Set 2:\n\nsummary $MARKER"}]}"""
            val service = serviceWith(captured) { request ->
                if (request.method == HttpMethod.Get) respond(messages, HttpStatusCode.OK) else respond("{}", HttpStatusCode.OK)
            }

            service.updateComment("proj", "7", "m1", "summary $MARKER")
            service.updateComment("proj", "7", "m1", "new summary $MARKER")

            assertEquals(1, captured.count { it.method == HttpMethod.Post })
        }

    private fun MockRequestHandleScope.inlineThreads(request: HttpRequestData): HttpResponseData =
        if (request.method == HttpMethod.Get) {
            respond(
                """)]}'
                {"a.kt": [{"id": "c1", "line": 4, "message": "x $MARKER", "patch_set": 2},
                          {"id": "c2", "line": 5, "message": "human note", "patch_set": 2},
                          {"id": "c4", "line": 9, "message": "old $MARKER", "patch_set": 2},
                          {"id": "c5", "line": 9, "message": "Done", "patch_set": 2, "in_reply_to": "c4"}],
                 "b.kt": [{"id": "c3", "line": 1, "message": "$MARKER y", "patch_set": 3}]}""",
                HttpStatusCode.OK,
            )
        } else {
            respond("{}", HttpStatusCode.OK)
        }

    @Test
    fun `checkAuth calls accounts self and maps 401 to false`() =
        runTest {
            val captured = mutableListOf<Captured>()
            assertEquals(true, serviceWith(captured).checkAuth())
            assertEquals(false, serviceWith(captured) { respond("", HttpStatusCode.Unauthorized) }.checkAuth())
            assertEquals("/a/accounts/self", captured.first().path)
        }

    @Test
    fun `capabilities advertise merge inline comments and thread resolution`() =
        runTest {
            val caps = GerritPlatformService()
            assertTrue(caps.supports(PlatformOperation.MERGE))
            assertTrue(caps.supports(PlatformOperation.POST_INLINE_COMMENTS))
            assertTrue(caps.supports(PlatformOperation.RESOLVE_DISCUSSIONS))
        }

    private companion object {
        const val MARKER = "<!-- karandash -->"
    }
}
