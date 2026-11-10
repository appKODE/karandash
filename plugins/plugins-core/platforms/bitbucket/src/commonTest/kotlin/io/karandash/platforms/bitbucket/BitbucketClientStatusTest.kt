package io.karandash.platforms.bitbucket

import io.karandash.api.exception.ProviderApiException
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BitbucketClientStatusTest {
    private fun client(status: HttpStatusCode) =
        BitbucketClient(
            httpClient = HttpClient(
                MockEngine {
                    respond(content = "boom", status = status)
                }
            ) { install(ContentNegotiation) { json(KarandashJson.Default) } },
            responseCache = null
        )

    @Test
    fun `void mutator throws on failing status`() =
        runTest { assertFailsWith<ProviderApiException> { client(HttpStatusCode.Forbidden).deleteComment("w", "r", 1, 2L) } }

    @Test
    fun `body-returning mutator throws on failing status`() =
        runTest { assertFailsWith<ProviderApiException> { client(HttpStatusCode.NotFound).postComment("w", "r", 1, "hi") } }

    @Test
    fun `pull request fetch throws the HTTP status instead of decoding the error body`() =
        runTest {
            val e = assertFailsWith<ProviderApiException> { client(HttpStatusCode.Unauthorized).fetchPullRequest("w", "r", "1") }
            assertEquals(401, e.statusCode)
        }

    @Test
    fun `lastRateLimit captured from response headers`() =
        runTest {
            val engine = MockEngine {
                respond(
                    content = "",
                    status = HttpStatusCode.NoContent,
                    headers = headersOf(
                        "X-RateLimit-Remaining" to listOf("42"),
                        "X-RateLimit-Limit" to listOf("1000"),
                        "X-RateLimit-Reset" to listOf("1760000000"),
                    ),
                )
            }
            val client = BitbucketClient(httpClient = HttpClient(engine), responseCache = null)
            assertNull(client.lastRateLimit())

            client.deleteComment("w", "r", 1, 2L)

            assertEquals(42, client.lastRateLimit()?.remaining)
            assertEquals(1000, client.lastRateLimit()?.limit)
            assertEquals(1760000000L, client.lastRateLimit()?.resetEpochSeconds)
        }
}
