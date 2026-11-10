package io.karandash.platforms.bitbucket

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

class BitbucketMergeOpsTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: JsonObject,
    )

    private suspend fun serviceWith(
        status: HttpStatusCode,
        captured: MutableList<Captured>,
    ): BitbucketPlatformService {
        val engine = MockEngine { request ->
            val text = (request.body as? TextContent)?.text ?: "{}"
            captured += Captured(request.method, request.url.encodedPath, Json.parseToJsonElement(text).jsonObject)
            respond("{}", status)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = BitbucketPlatformService()
        service.client.rebuild("test") { BitbucketClient(httpClient = http, responseCache = null) }
        return service
    }

    @Test
    fun `mergeMr posts the merge strategy and source branch flag`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.mergeMr("ws/repo", "7", MergeOptions(squash = true, deleteSourceBranch = true)).isSuccess)

            val call = captured.single()
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("/2.0/repositories/ws/repo/pullrequests/7/merge", call.path)
            assertEquals("\"squash\"", call.body["merge_strategy"].toString())
            assertEquals("true", call.body["close_source_branch"].toString())
        }

    @Test
    fun `closeMr declines the pull request and reopen stays unsupported`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(HttpStatusCode.OK, captured)

            assertTrue(service.closeMr("ws/repo", "7").isSuccess)
            assertFalse(service.reopenMr("ws/repo", "7").isSuccess)

            assertEquals(listOf("/2.0/repositories/ws/repo/pullrequests/7/decline"), captured.map { it.path })
        }

    @Test
    fun `mergeMr returns failure on an API error`() =
        runTest {
            val service = serviceWith(HttpStatusCode.BadRequest, mutableListOf())

            assertFalse(service.mergeMr("ws/repo", "7").isSuccess)
        }

    @Test
    fun `capabilities advertise merge and inline comments`() =
        runTest {
            val caps = BitbucketPlatformService()
            assertTrue(caps.supports(PlatformOperation.MERGE))
            assertTrue(caps.supports(PlatformOperation.POST_INLINE_COMMENTS))
            assertTrue(caps.supports(PlatformOperation.MANAGE_REVIEWERS))
        }
}
