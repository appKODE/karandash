package io.karandash.platforms.gitlab

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultStatus
import io.karandash.models.DiffRefs
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabServiceRoutingTest {
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private suspend fun service(route: (HttpMethod, String) -> String): GitLabPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            bodies += (request.body as? TextContent)?.text.orEmpty()
            respond(route(request.method, path), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitLabPlatformService()
        service.client.rebuild("test") { GitLabClient(token = "t", httpClient = http, responseCache = null) }
        return service
    }

    private fun mr(
        title: String = "t",
        labels: String = "",
    ) = """{"id": 1, "iid": 7, "title": "$title", "description": null, "source_branch": "s", "target_branch": "m",
        "author": {"id": 1, "username": "u", "name": "n"}, "state": "opened", "labels": [$labels]}"""

    @Test
    fun `a result status is posted as a commit status on the head commit`() =
        runTest {
            val service = service { _, _ -> "{}" }

            service.publishResultStatus(
                "42",
                "7",
                ResultStatus("karandash", false, "x".repeat(300), "s", "abc", emptyList(), "https://ci/r")
            )
            service.publishResultStatus("42", "7", ResultStatus("karandash", true, "ok", "s", "abc", emptyList(), null))

            assertEquals(List(2) { "POST /api/v4/projects/42/statuses/abc" }, calls)
            val failed = Json.parseToJsonElement(bodies[0]).jsonObject
            assertEquals("failed", failed.getValue("state").jsonPrimitive.content)
            assertEquals(
                255,
                failed
                    .getValue("description")
                    .jsonPrimitive.content.length
            )
            assertEquals("https://ci/r", failed.getValue("target_url").jsonPrimitive.content)
            val passed = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("success", passed.getValue("state").jsonPrimitive.content)
            assertNull(passed["target_url"])
        }

    @Test
    fun `inline comments become draft notes that are bulk published`() =
        runTest {
            val service = service { _, _ -> "{}" }
            val comments = listOf(InlineCommentData("a.kt", 5, null, "one"), InlineCommentData("b.kt", 9, 7, "two"))

            assertEquals("7", service.postInlineComments("42", "7", comments, DiffRefs("base", "head")))

            val base = "/api/v4/projects/42/merge_requests/7"
            assertEquals(List(2) { "POST $base/draft_notes" } + "POST $base/draft_notes/bulk_publish", calls)
            assertTrue(bodies[0].contains("\"start_sha\":\"base\""), bodies[0])
        }

    @Test
    fun `labels draft title and description updates go through the merge request endpoint`() =
        runTest {
            val service = service { _, _ -> mr(title = "Draft: work", labels = "\"a\", \"b\"") }

            service.addLabels("42", "7", listOf("c"))
            service.removeLabels("42", "7", listOf("a"))
            service.setDraft("42", "7", true)
            service.setDraft("42", "7", false)
            service.updateTitle("42", "7", "new")
            service.updateDescription("42", "7", "desc")

            val puts = bodies.filterIndexed { i, _ -> calls[i].startsWith("PUT") }
            assertEquals(6, puts.size)
            assertTrue(puts[0].contains("a,b,c"), puts[0])
            assertTrue(puts[1].contains("\"labels\":\"b\""), puts[1])
            assertTrue(puts[2].contains("Draft: work"), puts[2])
            assertTrue(puts[3].contains("\"title\":\"work\""), puts[3])
        }

    @Test
    fun `comment lookup skips system notes and delete targets the note`() =
        runTest {
            val notes = """[{"id": 1, "body": "MARK sys", "system": true}, {"id": 2, "body": "MARK user"}]"""
            val service = service { method, path -> if (method == HttpMethod.Get && path.endsWith("/notes")) notes else "[]" }

            assertEquals("MARK user", service.findCommentBody("42", "7", "MARK"))
            assertEquals(listOf("2"), service.findAllMatchingComments("42", "7", "MARK"))
            service.deleteComment("42", "7", "2")
            assertTrue("DELETE /api/v4/projects/42/merge_requests/7/notes/2" in calls)
        }

    @Test
    fun `list auth and capability probes use the api`() =
        runTest {
            val service = service { _, path -> if (path.endsWith("/merge_requests")) """[{"iid": 3}, {"iid": 4}]""" else "{}" }

            assertEquals(listOf("3", "4"), service.listOpenMergeRequestIds("42", "main", 10))
            service.checkAuth()
            assertTrue(calls.any { it == "GET /api/v4/user" })
            assertTrue(service.supports(PlatformOperation.RESULT_STATUS))
        }
}
