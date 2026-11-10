package io.karandash.platforms.gitea

import io.karandash.api.exception.ProviderApiException
import io.karandash.http.ProviderResponseCache
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

class GiteaClientStatusTest {
    @Test
    fun `body-returning mutator throws on failing status`() =
        runTest {
            val engine = MockEngine { respond(content = "boom", status = HttpStatusCode.InternalServerError) }
            val client =
                GiteaClient(
                    baseUrl = "https://gitea.example.com",
                    token = "t",
                    httpClient = HttpClient(engine) {
                        install(ContentNegotiation) { json(KarandashJson.Default) }
                    }
                )

            assertFailsWith<ProviderApiException> { client.postComment("o", "r", 1, "hi") }
        }

    @Test
    fun `failing mutator keeps only the head of a huge error body`() =
        runTest {
            val engine = MockEngine { respond(content = "x".repeat(10_000), status = HttpStatusCode.BadGateway) }
            val client =
                GiteaClient(
                    baseUrl = "https://gitea.example.com",
                    token = "t",
                    httpClient = HttpClient(engine) {
                        install(ContentNegotiation) { json(KarandashJson.Default) }
                    }
                )

            val error = assertFailsWith<ProviderApiException> { client.postComment("o", "r", 1, "hi") }
            assertEquals(500, error.responseBody.length)
        }

    @Test
    fun `paginated provider fetch is served from the ETag response cache`() =
        runTest {
            var calls = 0
            val engine = MockEngine {
                calls++
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf("ETag" to listOf("\"v1\""), "Content-Type" to listOf("application/json")),
                )
            }
            val client = GiteaProviderClient("https://gitea.example.com", HttpClient(engine), responseCache = ProviderResponseCache())

            assertEquals(emptyList(), client.fetchPullRequestFiles("o", "r", "1"))
            assertEquals(emptyList(), client.fetchPullRequestFiles("o", "r", "1"))
            assertEquals(1, calls)
        }
}
