package io.karandash.platforms.bitbucket

import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultAnnotation
import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitbucketServiceRoutingTest {
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private suspend fun service(route: (HttpMethod, String) -> String): BitbucketPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            bodies += (request.body as? TextContent)?.text.orEmpty()
            respond(route(request.method, path), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = BitbucketPlatformService()
        service.client.rebuild("test") { BitbucketClient(httpClient = http, responseCache = null) }
        return service
    }

    private fun pullRequest(title: String) =
        """{"id": 7, "title": "$title", "state": "OPEN", "author": {"uuid": "u1", "display_name": "n"},
        "source": {"branch": {"name": "s"}, "commit": {"hash": "h"}, "repository": {"uuid": "r", "name": "r", "full_name": "w/r"}},
        "destination": {"branch": {"name": "m"}, "commit": {"hash": "h"}, "repository": {"uuid": "r", "name": "r", "full_name": "w/r"}},
        "created_on": "c", "updated_on": "u"}"""

    private fun annotation(
        line: Int,
        level: ResultAnnotationLevel,
    ) = ResultAnnotation("src/A.kt", line, null, level, "t$line", "m$line")

    @Test
    fun `a result status is published as a code insights report with batched annotations`() =
        runTest {
            val service = service { _, _ -> "{}" }
            val levels = ResultAnnotationLevel.entries
            val annotations = (1..120).map { annotation(it, levels[it % levels.size]) }

            service.publishResultStatus(
                "w/r",
                "7",
                ResultStatus("karandash", false, "title", "summary", "abc", annotations, "https://ci/r")
            )

            val report = "/2.0/repositories/w/r/commit/abc/reports/karandash-karandash"
            assertEquals(listOf("PUT $report", "POST $report/annotations", "POST $report/annotations"), calls)
            val put = Json.parseToJsonElement(bodies[0]).jsonObject
            assertEquals("FAILED", put.getValue("result").jsonPrimitive.content)
            assertEquals("https://ci/r", put.getValue("link").jsonPrimitive.content)
            assertEquals(listOf(100, 20), bodies.drop(1).map { Json.parseToJsonElement(it).jsonArray.size })
            val severities = Json.parseToJsonElement(bodies[1]).jsonArray.map {
                it.jsonObject
                    .getValue("severity")
                    .jsonPrimitive.content
            }
            assertEquals(setOf("LOW", "MEDIUM", "HIGH"), severities.toSet())
        }

    @Test
    fun `a passing status without annotations sends only the report`() =
        runTest {
            val service = service { _, _ -> "{}" }

            service.publishResultStatus("w/r", "7", ResultStatus("karandash", true, "ok", "s", "abc", emptyList(), null))

            assertEquals(1, calls.size)
            assertEquals(
                "PASSED",
                Json
                    .parseToJsonElement(bodies.single())
                    .jsonObject
                    .getValue("result")
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `draft title and description updates put the pull request`() =
        runTest {
            val service = service { _, _ -> pullRequest("Draft: work") }

            service.setDraft("w/r", "7", true)
            service.setDraft("w/r", "7", false)
            service.updateTitle("w/r", "7", "new")
            service.updateDescription("w/r", "7", "desc")

            val puts = bodies.filterIndexed { i, _ -> calls[i].startsWith("PUT") }
            assertEquals(4, puts.size)
            assertTrue(puts[0].contains("\"title\":\"Draft: work\""), puts[0])
            assertTrue(puts[1].contains("\"title\":\"work\""), puts[1])
            assertTrue(puts[3].contains("\"description\":\"desc\""), puts[3])
        }

    @Test
    fun `setDraft prefixes a plain title`() =
        runTest {
            val service = service { _, _ -> pullRequest("work") }

            service.setDraft("w/r", "7", true)

            assertTrue(bodies.last().contains("\"title\":\"Draft: work\""), bodies.last())
        }

    @Test
    fun `user and capability probes read the user endpoint`() =
        runTest {
            val service = service { _, _ -> """{"uuid": "u1", "username": "alice", "display_name": "Alice"}""" }

            assertEquals("alice", service.currentUsername())
            service.checkAuth()
            assertTrue("GET /2.0/user" in calls)
            assertTrue(service.supports(PlatformOperation.RESULT_STATUS))
            assertEquals(null, service.rateLimitStatus())
        }
}
