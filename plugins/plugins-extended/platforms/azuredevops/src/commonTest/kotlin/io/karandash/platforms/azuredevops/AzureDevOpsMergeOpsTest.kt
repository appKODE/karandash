package io.karandash.platforms.azuredevops

import io.karandash.api.PluginContext
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.support.mapToJsonElement
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureDevOpsMergeOpsTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: JsonObject,
    )

    private val prJson =
        """{"pullRequestId": 7, "title": "t", "status": "active", "lastMergeSourceCommit": {"commitId": "abc123"}}"""

    private suspend fun serviceWith(
        status: HttpStatusCode,
        captured: MutableList<Captured>,
        config: Map<String, Any?> = emptyMap(),
    ): AzureDevOpsPlatformService {
        val engine = MockEngine { request ->
            val text = (request.body as? TextContent)?.text
            captured += Captured(request.method, request.url.encodedPath, Json.parseToJsonElement(text ?: "{}").jsonObject)
            val body = if (request.method == HttpMethod.Get) prJson else "{}"
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = AzureDevOpsPlatformService()
        assertTrue(service.initialize(context(config)))
        val baseUrl = config["url"] as? String ?: AzureDevOpsPlatformService.DEFAULT_BASE_URL
        service.commentClient.rebuild("test") { AzureDevOpsClient(baseUrl = baseUrl, token = "t", httpClient = http) }
        return service
    }

    @Test
    fun `mergeMr completes the pull request at its source commit`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            val options = MergeOptions(squash = true, deleteSourceBranch = true, commitMessage = "m")
            assertTrue(service.mergeMr("org/proj/repo", "7", options).isSuccess)

            val patch = captured.single { it.method == HttpMethod.Patch }
            assertEquals("/org/proj/_apis/git/repositories/repo/pullrequests/7", patch.path)
            assertEquals("\"completed\"", patch.body["status"].toString())
            assertEquals("""{"commitId":"abc123"}""", patch.body["lastMergeSourceCommit"].toString())
            assertEquals(
                """{"squashMerge":true,"deleteSourceBranch":true,"mergeCommitMessage":"m"}""",
                patch.body["completionOptions"].toString(),
            )
        }

    @Test
    fun `closeMr abandons and reopenMr reactivates the pull request`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.closeMr("org/proj/repo", "7").isSuccess)
            assertTrue(service.reopenMr("org/proj/repo", "7").isSuccess)

            assertEquals(listOf("\"abandoned\"", "\"active\""), captured.map { it.body["status"].toString() })
            assertTrue(captured.all { it.method == HttpMethod.Patch })
        }

    @Test
    fun `setDraft patches instead of put`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            service.setDraft("org/proj/repo", "7", true)

            assertEquals(HttpMethod.Patch, captured.single().method)
            assertEquals("true", captured.single().body["isDraft"].toString())
        }

    @Test
    fun `merge and state changes fail on an API error`() =
        runTest {
            val service = serviceWith(HttpStatusCode.Conflict, mutableListOf())

            assertFalse(service.mergeMr("org/proj/repo", "7").isSuccess)
            assertFalse(service.closeMr("org/proj/repo", "7").isSuccess)
        }

    @Test
    fun `configured organization and url apply to short project IDs and PR URLs`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val config = mapOf("url" to "https://tfs.corp.test/tfs", "organization" to "coll")
            val service = serviceWith(HttpStatusCode.OK, captured, config)

            assertTrue(service.closeMr("proj/repo", "7").isSuccess)
            assertEquals("/tfs/coll/proj/_apis/git/repositories/repo/pullrequests/7", captured.single().path)

            val parsed = service.parseMergeRequestUrl("https://tfs.corp.test/tfs/coll/proj/_git/repo/pullrequest/9")
            assertEquals("https://tfs.corp.test/tfs", parsed?.baseUrl)
            assertEquals("coll/proj/repo", parsed?.projectId)
            assertEquals("9", parsed?.mrId)
            assertEquals("https://dev.azure.com", service.parseMergeRequestUrl("https://dev.azure.com/o/p/_git/r/pullrequest/1")?.baseUrl)
        }

    @Test
    fun `initialize rejects a non-https url`() =
        runTest {
            assertFalse(AzureDevOpsPlatformService().initialize(context(mapOf("url" to "http://tfs.corp.test"))))
        }

    @Test
    fun `provider client fetches from the configured url`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine = MockEngine { request ->
                paths += request.url.host + request.url.encodedPath
                respond("""{"value": []}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }

            AzureDevOpsProviderClient(http, token = "t", baseUrl = "https://tfs.corp.test/tfs").fetchStatuses("c", "p", "r", "7")

            assertEquals(listOf("tfs.corp.test/tfs/c/p/_apis/git/repositories/r/pullRequests/7/statuses"), paths)
        }

    @Test
    fun `checkAuth uses connectionData when an organization is configured`() =
        runTest {
            val captured = mutableListOf<Captured>()
            assertNull(serviceWith(HttpStatusCode.OK, captured).checkAuth())
            assertEquals(true, serviceWith(HttpStatusCode.OK, captured, mapOf("organization" to "org")).checkAuth())
            assertEquals(false, serviceWith(HttpStatusCode.Unauthorized, captured, mapOf("organization" to "org")).checkAuth())
            assertEquals("/org/_apis/connectionData", captured.first().path)
        }

    @Test
    fun `capabilities advertise merge and inline comments`() =
        runTest {
            val caps = AzureDevOpsPlatformService()
            assertTrue(caps.supports(PlatformOperation.MERGE))
            assertTrue(caps.supports(PlatformOperation.POST_INLINE_COMMENTS))
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
