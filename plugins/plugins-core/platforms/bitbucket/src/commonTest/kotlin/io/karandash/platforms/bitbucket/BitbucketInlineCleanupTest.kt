@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.bitbucket

import io.karandash.api.platform.PlatformOperation
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.SupersededInlineComment
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
import kotlin.test.assertTrue

/** An earlier run's inline comments are edited in place and their threads resolved, never deleted and re-posted. */
class BitbucketInlineCleanupTest {
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private suspend fun service(): BitbucketPlatformService {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                val values = listOf(
                    """{"id":1,"content":{"raw":"old <!-- kd -->"},"inline":{"path":"a.kt","to":3}}""",
                    """{"id":2,"content":{"raw":"<!-- sum --> summary"}}""",
                    """{"id":3,"content":{"raw":"human"},"inline":{"path":"a.kt","to":4}}""",
                    """{"id":4,"content":{"raw":"cached <!-- kd -->"},"inline":{"path":"b.kt","to":5}}""",
                    """{"id":5,"content":{"raw":"${SupersededInlineComment.NOTE}\n\nolder <!-- kd -->"},"inline":{"path":"a.kt","to":6}}""",
                    """{"id":6,"content":{"raw":"<!-- sum --> summary again"}}""",
                    """{"id":7,"content":{"raw":"> quoting <!-- sum --> in a human reply"}}""",
                )
                respond(
                    values.joinToString(",", """{"values":[""", "]}").replace("\n", "\\n"),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                requests += "${request.method.value} ${request.url.encodedPath}"
                (request.body as? TextContent)?.let { bodies += it.text }
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = BitbucketPlatformService()
        service.client.rebuild("test") { BitbucketClient(httpClient = http, responseCache = null) }
        return service
    }

    @Test
    fun `resolveMarkedInlineCommentsForFiles edits marked comments on re-reviewed files in place and deletes nothing`() =
        runTest {
            val count = service().resolveMarkedInlineCommentsForFiles("ws/repo", "7", "<!-- kd -->", setOf("a.kt"))

            assertEquals(1, count)
            val comment = "/2.0/repositories/ws/repo/pullrequests/7/comments/1"
            assertEquals(listOf("PUT $comment", "POST $comment/resolve"), requests)
            assertTrue(bodies.single().contains(SupersededInlineComment.NOTE))
        }

    @Test
    fun `resolveMarkedInlineComments edits every pending marked inline comment`() =
        runTest {
            val count = service().resolveMarkedInlineComments("ws/repo", "7", "<!-- kd -->")

            assertEquals(2, count)
            assertEquals(
                listOf("PUT 1", "POST 1/resolve", "PUT 4", "POST 4/resolve"),
                requests.map { it.substringBefore(' ') + " " + it.substringAfter("/comments/") },
            )
        }

    @Test
    fun `findAllMatchingComments returns every summary copy and deleteComment removes one`() =
        runTest {
            val service = service()

            assertEquals(listOf("2", "6"), service.findAllMatchingComments("ws/repo", "7", "<!-- sum -->"))
            service.deleteComment("ws/repo", "7", "6")
            assertEquals(listOf("DELETE /2.0/repositories/ws/repo/pullrequests/7/comments/6"), requests)
        }

    @Test
    fun `bitbucket advertises thread resolution`() =
        runTest {
            assertEquals(true, service().supports(PlatformOperation.RESOLVE_DISCUSSIONS))
        }

    @Test
    fun `resolveThread replies with the note under the comment then resolves it`() =
        runTest {
            val service = service()

            assertTrue(service.resolveThread("ws/repo", "7", "4", "done").isSuccess)
            assertTrue(service.resolveThread("ws/repo", "7", "x", null).isFailure)

            val comments = "/2.0/repositories/ws/repo/pullrequests/7/comments"
            assertEquals(listOf("POST $comments", "POST $comments/4/resolve"), requests)
            assertEquals("""{"content":{"raw":"done"},"parent":{"id":4}}""", bodies.single())
        }
}
