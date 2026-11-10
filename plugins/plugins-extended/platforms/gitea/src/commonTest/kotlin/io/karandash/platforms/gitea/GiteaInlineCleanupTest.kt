@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitea

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

/** Before 1.26 Gitea cannot resolve threads: an earlier run's inline comments are edited in place, never deleted. */
class GiteaInlineCleanupTest {
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private suspend fun service(version: String = "1.25.2"): GiteaPlatformService {
        val superseded = "${SupersededInlineComment.NOTE}\\n\\nolder <!-- kd -->"
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                val path = request.url.encodedPath
                val json = if (path.endsWith("/version")) {
                    """{"version":"$version"}"""
                } else if (path.endsWith("/reviews")) {
                    """[{"id":5}]"""
                } else if (path.contains("/issues/")) {
                    """[{"id":10,"body":"<!-- sum --> summary"},{"id":11,"body":"human"},{"id":12,"body":"<!-- sum --> again"},{"id":13,"body":"> quoting <!-- sum --> in a human reply"}]"""
                } else {
                    """[{"id":1,"body":"old <!-- kd -->","path":"a.kt"},{"id":2,"body":"human","path":"a.kt"},""" +
                        """{"id":3,"body":"<!-- kd --> cached","path":"b.kt"},{"id":4,"body":"$superseded","path":"a.kt"},""" +
                        """{"id":6,"body":"resolved <!-- kd -->","path":"a.kt","resolver":{"id":1,"login":"bot"}}]"""
                }
                respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                requests += "${request.method.value} ${request.url.encodedPath}"
                (request.body as? TextContent)?.let { bodies += it.text }
                respond("", HttpStatusCode.OK)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GiteaPlatformService()
        service.commentClient.rebuild("test") { GiteaClient(baseUrl = "https://gitea.test", token = "t", httpClient = http) }
        return service
    }

    @Test
    fun `resolveMarkedInlineCommentsForFiles edits marked comments on re-reviewed files in place and deletes nothing`() =
        runTest {
            val count = service().resolveMarkedInlineCommentsForFiles("o/r", "7", "<!-- kd -->", setOf("a.kt"))

            assertEquals(1, count)
            assertEquals(listOf("PATCH /api/v1/repos/o/r/issues/comments/1"), requests)
            assertTrue(bodies.single().contains("Outdated"))
        }

    @Test
    fun `resolveMarkedInlineComments edits every pending marked review comment`() =
        runTest {
            assertEquals(2, service().resolveMarkedInlineComments("o/r", "7", "<!-- kd -->"))
            assertEquals(
                listOf("PATCH /api/v1/repos/o/r/issues/comments/1", "PATCH /api/v1/repos/o/r/issues/comments/3"),
                requests,
            )
        }

    @Test
    fun `on Gitea 1_26 marked conversations are resolved instead of edited`() =
        runTest {
            assertEquals(2, service("1.26.0").resolveMarkedInlineComments("o/r", "7", "<!-- kd -->"))
            assertEquals(
                listOf("POST /api/v1/repos/o/r/pulls/comments/1/resolve", "POST /api/v1/repos/o/r/pulls/comments/3/resolve"),
                requests,
            )
        }

    @Test
    fun `Forgejo reports its Gitea base version and keeps the in-place edit`() =
        runTest {
            assertEquals(2, service("11.0.1+gitea-1.22.0").resolveMarkedInlineComments("o/r", "7", "<!-- kd -->"))
            assertTrue(requests.all { it.startsWith("PATCH ") })
        }

    @Test
    fun `findAllMatchingComments returns every summary copy and deleteComment removes one`() =
        runTest {
            val service = service()

            assertEquals(listOf("10", "12"), service.findAllMatchingComments("o/r", "7", "<!-- sum -->"))
            service.deleteComment("o/r", "7", "12")
            assertEquals(listOf("DELETE /api/v1/repos/o/r/issues/comments/12"), requests)
        }
}
