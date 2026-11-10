package io.karandash.platforms.gitlab

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

class GitLabMergeOpsTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: JsonObject,
    )

    private suspend fun serviceWith(
        status: HttpStatusCode,
        captured: MutableList<Captured>,
    ): GitLabPlatformService {
        val engine = MockEngine { request ->
            val text = (request.body as? TextContent)?.text ?: "{}"
            captured += Captured(request.method, request.url.encodedPath, Json.parseToJsonElement(text).jsonObject)
            respond("{}", status)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitLabPlatformService()
        service.client.rebuild("test") { GitLabClient(token = "t", httpClient = http, responseCache = null) }
        return service
    }

    @Test
    fun `mergeMr sends PUT to the merge endpoint with the merge options`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            val options = MergeOptions(squash = true, deleteSourceBranch = true, commitMessage = "m")
            val result = service.mergeMr("42", "7", options)

            assertTrue(result.isSuccess)
            val call = captured.single()
            assertEquals(HttpMethod.Put, call.method)
            assertEquals("/api/v4/projects/42/merge_requests/7/merge", call.path)
            assertEquals("true", call.body["squash"].toString())
            assertEquals("true", call.body["should_remove_source_branch"].toString())
            assertEquals("\"m\"", call.body["merge_commit_message"].toString())
        }

    @Test
    fun `closeMr and reopenMr send the state event`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.closeMr("42", "7").isSuccess)
            assertTrue(service.reopenMr("42", "7").isSuccess)

            assertEquals(listOf("\"close\"", "\"reopen\""), captured.map { it.body["state_event"].toString() })
            assertTrue(captured.all { it.path == "/api/v4/projects/42/merge_requests/7" })
        }

    @Test
    fun `mergeMr returns failure on an API error`() =
        runTest {
            val service = serviceWith(HttpStatusCode.MethodNotAllowed, mutableListOf())

            assertFalse(service.mergeMr("42", "7").isSuccess)
        }

    @Test
    fun `capabilities advertise merge and reviewer management`() =
        runTest {
            val caps = GitLabPlatformService()
            assertTrue(caps.supports(PlatformOperation.MERGE))
            assertTrue(caps.supports(PlatformOperation.MANAGE_REVIEWERS))
        }
}
