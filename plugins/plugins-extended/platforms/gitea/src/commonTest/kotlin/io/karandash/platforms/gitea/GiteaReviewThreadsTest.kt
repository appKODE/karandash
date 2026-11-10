@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitea

import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GiteaReviewThreadsTest {
    private val writes = mutableListOf<String>()

    private suspend fun service(version: String): GiteaPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (request.method != HttpMethod.Get) {
                writes += "${request.method.value} $path ${(request.body as? TextContent)?.text.orEmpty()}"
                return@MockEngine respond("""{"id":99}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val json = when {
                path.endsWith("/version") -> {
                    """{"version":"$version"}"""
                }

                path.endsWith("/reviews") -> {
                    """[{"id":5}]"""
                }

                else -> {
                    """[{"id":1,"body":"root","path":"a.kt","position":3,"commit_id":"h","user":{"id":1,"login":"dev"}},""" +
                        """{"id":2,"body":"reply","path":"a.kt","position":3,"resolver":{"id":2,"login":"lead"}},""" +
                        """{"id":4,"body":"old side","path":"b.kt","original_position":7,"commit_id":"h"}]"""
                }
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GiteaPlatformService()
        service.commentClient.rebuild("test") { GiteaClient(baseUrl = "https://gitea.test", token = "t", httpClient = http) }
        return service
    }

    @Test
    fun `listReviewThreads groups comments by file and line`() =
        runTest {
            val threads = service("1.25.0").listReviewThreads("o/r", "7")

            assertEquals(listOf("1", "4"), threads.map { it.id })
            assertEquals(listOf("1", "2"), threads[0].notes.map { it.id })
            assertTrue(threads[0].resolved)
            assertEquals("dev", threads[0].notes[0].author)
            assertEquals(3, threads[0].position?.newLine)
            assertEquals(7, threads[1].position?.oldLine)
            assertEquals(null, threads[1].position?.newLine)
        }

    @Test
    fun `replyToThread posts a review comment on the same line and updateNote edits in place`() =
        runTest {
            val service = service("1.25.0")

            assertTrue(service.replyToThread("o/r", "7", "4", "thanks").isSuccess)
            assertTrue(service.updateNote("o/r", "7", "4", "4", "edited").isSuccess)

            assertTrue(writes[0].startsWith("POST /api/v1/repos/o/r/pulls/7/reviews "), writes[0])
            assertTrue(""""old_position":7""" in writes[0] && """"new_position":0""" in writes[0], writes[0])
            assertTrue(writes[1].startsWith("PATCH /api/v1/repos/o/r/issues/comments/4 "), writes[1])
        }

    @Test
    fun `resolveThread needs Gitea 1_26`() =
        runTest {
            val old = service("1.25.0")
            assertIs<UnsupportedPlatformOperationException>(old.resolveThread("o/r", "7", "1").exceptionOrNull())
            assertFalse(old.supports(PlatformOperation.RESOLVE_DISCUSSIONS))
            assertTrue(writes.isEmpty())

            val current = service("1.26.1")
            assertTrue(current.supports(PlatformOperation.RESOLVE_DISCUSSIONS))
            assertTrue(current.resolveThread("o/r", "7", "1", note = "done").isSuccess)
            assertEquals("POST /api/v1/repos/o/r/pulls/comments/1/resolve ", writes[0])
            assertTrue(writes[1].startsWith("POST /api/v1/repos/o/r/pulls/7/reviews "), writes[1])
        }

    @Test
    fun `version check reads the Gitea part of a Forgejo version`() {
        assertTrue(canResolveConversations("1.26.0"))
        assertTrue(canResolveConversations("2.0.0+dev-12"))
        assertFalse(canResolveConversations("1.25.4"))
        assertFalse(canResolveConversations("11.0.1+gitea-1.22.0"))
        assertFalse(canResolveConversations(null))
        assertFalse(canResolveConversations("unknown"))
    }

    @Test
    fun `reviewers are requested and withdrawn by login`() =
        runTest {
            val current = service("1.22.0")
            assertTrue(current.addReviewers("o/r", "7", listOf("ann", "bob")).isSuccess)
            assertTrue(current.removeReviewers("o/r", "7", listOf("bob")).isSuccess)
            assertEquals("POST /api/v1/repos/o/r/pulls/7/requested_reviewers {\"reviewers\":[\"ann\",\"bob\"]}", writes[0])
            assertEquals("DELETE /api/v1/repos/o/r/pulls/7/requested_reviewers {\"reviewers\":[\"bob\"]}", writes[1])
            assertTrue(current.supports(PlatformOperation.MANAGE_REVIEWERS))
        }
}
