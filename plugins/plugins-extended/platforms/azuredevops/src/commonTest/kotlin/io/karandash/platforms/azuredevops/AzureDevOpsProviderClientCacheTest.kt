package io.karandash.platforms.azuredevops

import io.karandash.http.ProviderResponseCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AzureDevOpsProviderClientCacheTest {
    @Test
    fun `paginated commits are served from the ETag response cache`() =
        runTest {
            var calls = 0
            val engine = MockEngine { _ ->
                calls++
                respond(
                    content = """{"count":0,"value":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("ETag" to listOf("\"v1\""), "Content-Type" to listOf("application/json")),
                )
            }
            val client = AzureDevOpsProviderClient(HttpClient(engine), responseCache = ProviderResponseCache())

            assertEquals(emptyList(), client.fetchCommits("o", "p", "r", "1"))
            assertEquals(emptyList(), client.fetchCommits("o", "p", "r", "1"))
            assertEquals(1, calls)
        }

    @Test
    fun `lastRateLimit captured from response headers`() =
        runTest {
            val engine = MockEngine {
                respond(
                    content = """{"count":0,"value":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Content-Type" to listOf("application/json"),
                        "X-RateLimit-Remaining" to listOf("5"),
                        "X-RateLimit-Limit" to listOf("200"),
                        "X-RateLimit-Reset" to listOf("1760000999"),
                    ),
                )
            }
            val client = AzureDevOpsProviderClient(HttpClient(engine))

            client.fetchCommits("o", "p", "r", "1")

            assertEquals(5, client.lastRateLimit()?.remaining)
            assertEquals(200, client.lastRateLimit()?.limit)
        }
}
