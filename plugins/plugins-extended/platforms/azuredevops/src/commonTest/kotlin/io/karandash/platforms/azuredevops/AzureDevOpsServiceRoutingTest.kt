package io.karandash.platforms.azuredevops

import io.karandash.api.PluginContext
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultStatus
import io.karandash.api.support.mapToJsonElement
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureDevOpsServiceRoutingTest {
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val threads =
        """{"value":[
        {"id":3,"status":"fixed","threadContext":{"filePath":"/src/A.kt","rightFileStart":{"line":4,"offset":1},
         "rightFileEnd":{"line":6,"offset":1}},
         "comments":[{"id":1,"content":"fix it","author":{"id":"u1","displayName":"Ann","uniqueName":"ann@x"},"publishedDate":"d1"},
                     {"id":2,"content":"sys","commentType":"system"}]},
        {"id":4,"status":"active","comments":[{"id":1,"content":"LGTM","author":{"id":"u2","displayName":"Bob"}}]},
        {"comments":[]}]}"""

    private suspend fun service(route: (HttpMethod, String) -> String): AzureDevOpsPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            bodies += (request.body as? TextContent)?.text.orEmpty()
            respond(route(request.method, path), HttpStatusCode.OK, jsonHeaders)
        }
        val svc = AzureDevOpsPlatformService(engine)
        assertTrue(svc.initialize(context(mapOf("token" to "t", "organization" to "org"))))
        return svc
    }

    @Test
    fun `review threads are mapped with position resolution and system notes`() =
        runTest {
            val svc = service { _, _ -> threads }

            val mapped = svc.listReviewThreads("org/proj/repo", "7")

            assertEquals(listOf("3", "4"), mapped.map { it.id })
            assertTrue(mapped[0].resolved)
            assertEquals("src/A.kt", mapped[0].position?.newPath)
            assertEquals(6, mapped[0].position?.newLine)
            assertEquals(listOf(false, true), mapped[0].notes.map { it.system })
            assertEquals("ann@x", mapped[0].notes[0].author)
            assertNull(mapped[1].position)
            assertEquals("Bob", mapped[1].notes.single().author)
            assertEquals(listOf("LGTM"), svc.fetchApprovalSignals("org/proj/repo", "7").map { it.text }.filter { it == "LGTM" })
        }

    @Test
    fun `reply to a thread posts a comment under the thread`() =
        runTest {
            val svc = service { _, _ -> "{}" }

            svc.replyToThread("org/proj/repo", "7", "5", "thanks")

            assertTrue(calls.any { it.startsWith("POST") && it.contains("/threads/5/comments") }, calls.toString())
            assertTrue(bodies.last().contains("thanks"), bodies.last())
        }

    @Test
    fun `current user comes from connection data and search resolves the author identity first`() =
        runTest {
            val hits =
                """{"value":[{"pullRequestId":1,"title":"fix login","status":"active","createdBy":{"uniqueName":"ann@x"},
                "repository":{"name":"repo"}},
                {"pullRequestId":2,"title":"other","status":"completed"},{"pullRequestId":3,"title":"login again","status":"abandoned"}]}"""
            val svc =
                service { _, path ->
                    when {
                        path.endsWith("/connectionData") -> """{"authenticatedUser":{"id":"me-id"}}"""
                        path.endsWith("/identities") -> """{"value":[{"id":"ident-1"}]}"""
                        path.endsWith("/pullrequests") -> hits
                        else -> "{}"
                    }
                }

            assertEquals("me-id", svc.currentUsername())
            val found = svc.searchMergeRequests("org/proj/repo", "login", "ann", 10)

            assertEquals(listOf("1", "3"), found.map { it.id })
            assertEquals(listOf("opened", "closed"), found.map { it.state })
            assertTrue(calls.any { it.endsWith("/identities") })
        }

    @Test
    fun `search returns nothing when the author has no identity`() =
        runTest {
            val svc = service { _, path -> if (path.endsWith("/identities")) """{"value":[]}""" else "{}" }

            assertTrue(svc.searchMergeRequests("org/proj/repo", null, "ghost", 5).isEmpty())
            assertTrue(calls.none { it.endsWith("/pullrequests") })
        }

    @Test
    fun `open ids and file content come from the repository api`() =
        runTest {
            val svc =
                service { _, path ->
                    if (path.endsWith("/items")) "file body" else """{"value":[{"pullRequestId":11},{"pullRequestId":12}]}"""
                }

            assertEquals(listOf("11", "12"), svc.listOpenMergeRequestIds("org/proj/repo", "main", 5))
            assertEquals("file body", svc.fetchFileContent("org/proj/repo", "main", "a.kt"))
            assertEquals("file body", svc.fetchFileContent("org/proj/repo", "a".repeat(40), "a.kt"))
        }

    @Test
    fun `a result status is published as a pull request status`() =
        runTest {
            val svc = service { _, _ -> "{}" }

            svc.publishResultStatus(
                "org/proj/repo",
                "7",
                ResultStatus("karandash", false, "x".repeat(300), "s", "abc", emptyList(), "https://ci/r")
            )
            svc.publishResultStatus("org/proj/repo", "7", ResultStatus("karandash", true, "ok", "s", "abc", emptyList(), null))

            assertTrue(calls.all { it.startsWith("POST") && it.endsWith("/pullRequests/7/statuses") }, calls.toString())
            val failed = Json.parseToJsonElement(bodies[0]).jsonObject
            assertEquals("failed", failed.getValue("state").jsonPrimitive.content)
            assertEquals(
                255,
                failed
                    .getValue("description")
                    .jsonPrimitive.content.length
            )
            assertEquals("https://ci/r", failed.getValue("targetUrl").jsonPrimitive.content)
            val passed = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("succeeded", passed.getValue("state").jsonPrimitive.content)
            assertNull(passed["targetUrl"])
            assertTrue(svc.supports(PlatformOperation.RESULT_STATUS))
            assertTrue(svc.renderSuggestionBlock("code").contains("code"))
        }

    private fun context(config: Map<String, Any?>): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
