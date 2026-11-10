@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.github

import io.karandash.logger.NoOpLogger
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

/** Without GraphQL, GitHub threads cannot be resolved: earlier inline comments are edited in place, never deleted. */
class GitHubInlineCleanupTest {
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private suspend fun service(forbiddenPatchPath: String? = null): GitHubPlatformService {
        val superseded = "${SupersededInlineComment.NOTE}\\n\\nolder <!-- kd -->"
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                val json = if (request.url.encodedPath.contains("/issues/")) {
                    """[{"id":10,"body":"<!-- sum --> summary"},{"id":11,"body":"human"},{"id":12,"body":"<!-- sum --> again"},{"id":13,"body":"> quoting <!-- sum --> in a human reply"}]"""
                } else {
                    """[{"id":1,"body":"old <!-- kd -->","path":"a.kt"},{"id":2,"body":"human","path":"a.kt"},""" +
                        """{"id":3,"body":"<!-- kd --> cached","path":"b.kt"},{"id":4,"body":"$superseded","path":"a.kt"}]"""
                }
                respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                requests += "${request.method.value} ${request.url.encodedPath}"
                (request.body as? TextContent)?.let { bodies += it.text }
                val status = if (request.url.encodedPath == forbiddenPatchPath) HttpStatusCode.Forbidden else HttpStatusCode.OK
                respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitHubPlatformService()
        service.client.rebuild("test") {
            GitHubClient(baseUrl = "https://api.github.test", httpClient = http, logger = NoOpLogger, responseCache = null)
        }
        return service
    }

    @Test
    fun `resolveMarkedInlineCommentsForFiles edits marked comments on re-reviewed files in place and deletes nothing`() =
        runTest {
            val count = service().resolveMarkedInlineCommentsForFiles("o/r", "7", "<!-- kd -->", setOf("a.kt"))

            assertEquals(1, count)
            assertEquals(listOf("PATCH /repos/o/r/pulls/comments/1"), requests)
            assertTrue(bodies.single().contains("Outdated"))
        }

    @Test
    fun `resolveMarkedInlineComments edits every pending marked review comment`() =
        runTest {
            assertEquals(2, service().resolveMarkedInlineComments("o/r", "7", "<!-- kd -->"))
            assertEquals(listOf("PATCH /repos/o/r/pulls/comments/1", "PATCH /repos/o/r/pulls/comments/3"), requests)
        }

    @Test
    fun `a comment that cannot be edited is skipped and the rest are still marked`() =
        runTest {
            val count = service(forbiddenPatchPath = "/repos/o/r/pulls/comments/1")
                .resolveMarkedInlineComments("o/r", "7", "<!-- kd -->")

            assertEquals(1, count)
            assertEquals(listOf("PATCH /repos/o/r/pulls/comments/1", "PATCH /repos/o/r/pulls/comments/3"), requests)
        }

    @Test
    fun `findAllMatchingComments returns every summary copy and deleteComment removes one`() =
        runTest {
            val service = service()

            assertEquals(listOf("10", "12"), service.findAllMatchingComments("o/r", "7", "<!-- sum -->"))
            service.deleteComment("o/r", "7", "12")
            assertEquals(listOf("DELETE /repos/o/r/issues/comments/12"), requests)
        }

    @Test
    fun `listDismissedInlineComments returns marked review comments with a thumbs-down`() =
        runTest {
            val body =
                """[{"id":1,"body":"a <!-- kd -->","reactions":{"-1":1}},""" +
                    """{"id":2,"body":"b <!-- kd -->","reactions":{"-1":0,"+1":2}},""" +
                    """{"id":3,"body":"human","reactions":{"-1":3}}]"""
            val engine = MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val service = GitHubPlatformService()
            service.client.rebuild("test") {
                GitHubClient(baseUrl = "https://api.github.test", httpClient = http, logger = NoOpLogger, responseCache = null)
            }

            assertEquals(listOf("a <!-- kd -->"), service.listDismissedInlineComments("o/r", "7", "<!-- kd -->"))
        }
}
