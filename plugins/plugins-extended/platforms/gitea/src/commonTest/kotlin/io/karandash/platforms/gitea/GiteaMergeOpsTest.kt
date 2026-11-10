package io.karandash.platforms.gitea

import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.PlatformOperation
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GiteaMergeOpsTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: JsonObject,
    )

    private suspend fun serviceWith(
        status: HttpStatusCode,
        captured: MutableList<Captured>,
    ): GiteaPlatformService {
        val engine = MockEngine { request ->
            val text = (request.body as? TextContent)?.text ?: "{}"
            captured += Captured(request.method, request.url.encodedPath, Json.parseToJsonElement(text).jsonObject)
            respond("{}", status)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GiteaPlatformService()
        service.commentClient.rebuild("test") { GiteaClient(baseUrl = "https://gitea.test", token = "t", httpClient = http) }
        return service
    }

    @Test
    fun `mergeMr posts the merge style and branch deletion flag`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.mergeMr("o/r", "7", MergeOptions(squash = true, deleteSourceBranch = true, commitMessage = "m")).isSuccess)

            val call = captured.single()
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("/api/v1/repos/o/r/pulls/7/merge", call.path)
            assertEquals("\"squash\"", call.body["Do"].toString())
            assertEquals("true", call.body["delete_branch_after_merge"].toString())
            assertEquals("\"m\"", call.body["MergeMessageField"].toString())
        }

    @Test
    fun `closeMr and reopenMr patch the pull request state`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.closeMr("o/r", "7").isSuccess)
            assertTrue(service.reopenMr("o/r", "7").isSuccess)

            assertEquals(listOf("\"closed\"", "\"open\""), captured.map { it.body["state"].toString() })
            assertTrue(captured.all { it.method == HttpMethod.Patch && it.path == "/api/v1/repos/o/r/pulls/7" })
        }

    @Test
    fun `mergeMr returns failure on an API error`() =
        runTest {
            val service = serviceWith(HttpStatusCode.MethodNotAllowed, mutableListOf())

            assertFalse(service.mergeMr("o/r", "7").isSuccess)
        }

    @Test
    fun `checkAuth maps 2xx to true and 401 to false`() =
        runTest {
            val captured = mutableListOf<Captured>()
            assertEquals(true, serviceWith(HttpStatusCode.OK, captured).checkAuth())
            assertEquals(false, serviceWith(HttpStatusCode.Unauthorized, captured).checkAuth())
            assertEquals("/api/v1/user", captured.first().path)
        }

    @Test
    fun `capabilities advertise merge and inline comments`() =
        runTest {
            val caps = GiteaPlatformService()
            assertTrue(caps.supports(PlatformOperation.MERGE))
            assertTrue(caps.supports(PlatformOperation.POST_INLINE_COMMENTS))
        }
}
