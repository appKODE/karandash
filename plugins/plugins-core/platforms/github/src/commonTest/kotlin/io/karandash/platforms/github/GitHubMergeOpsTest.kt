package io.karandash.platforms.github

import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.RateLimitInfo
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.NoOpLogger
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

class GitHubMergeOpsTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: JsonObject,
    )

    private suspend fun serviceWith(
        status: HttpStatusCode,
        captured: MutableList<Captured>,
    ): GitHubPlatformService {
        val engine = MockEngine { request ->
            val text = (request.body as? TextContent)?.text ?: "{}"
            captured += Captured(request.method, request.url.encodedPath, Json.parseToJsonElement(text).jsonObject)
            respond("{}", status)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitHubPlatformService()
        service.client.rebuild("test") {
            GitHubClient(baseUrl = "https://api.github.test", httpClient = http, logger = NoOpLogger, responseCache = null)
        }
        return service
    }

    @Test
    fun `mergeMr sends PUT to the merge endpoint with squash as merge method`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.mergeMr("o/r", "7", MergeOptions(squash = true, commitMessage = "m")).isSuccess)

            val call = captured.single()
            assertEquals(HttpMethod.Put, call.method)
            assertEquals("/repos/o/r/pulls/7/merge", call.path)
            assertEquals("\"squash\"", call.body["merge_method"].toString())
            assertEquals("\"m\"", call.body["commit_message"].toString())
        }

    @Test
    fun `closeMr and reopenMr patch the pull request state`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.closeMr("o/r", "7").isSuccess)
            assertTrue(service.reopenMr("o/r", "7").isSuccess)

            assertEquals(listOf("\"closed\"", "\"open\""), captured.map { it.body["state"].toString() })
            assertTrue(captured.all { it.method == HttpMethod.Patch && it.path == "/repos/o/r/pulls/7" })
        }

    @Test
    fun `mergeMr returns failure when the pull request is not mergeable`() =
        runTest {
            val service = serviceWith(HttpStatusCode.MethodNotAllowed, mutableListOf())

            assertFalse(service.mergeMr("o/r", "7").isSuccess)
        }

    @Test
    fun `rateLimitStatus reads the core bucket and is null when the endpoint is missing`() =
        runTest {
            val captured = mutableListOf<String>()
            val engine = MockEngine { request ->
                captured += request.url.encodedPath
                if (request.url.host == "ghe.test") {
                    respond("{}", HttpStatusCode.NotFound)
                } else {
                    respond(
                        """{"resources": {}, "rate": {"limit": 5000, "remaining": 4990, "reset": 1700000000, "used": 10}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val service = GitHubPlatformService()
            service.client.rebuild("test") {
                GitHubClient(baseUrl = "https://api.github.test", httpClient = http, logger = NoOpLogger, responseCache = null)
            }
            assertEquals(RateLimitInfo(remaining = 4990, limit = 5000, resetEpochSeconds = 1700000000), service.rateLimitStatus())

            service.client.rebuild("ghe") {
                GitHubClient(baseUrl = "https://ghe.test/api/v3", httpClient = http, logger = NoOpLogger, responseCache = null)
            }
            assertNull(service.rateLimitStatus())
            assertEquals(listOf("/rate_limit", "/api/v3/rate_limit"), captured)
        }
}
