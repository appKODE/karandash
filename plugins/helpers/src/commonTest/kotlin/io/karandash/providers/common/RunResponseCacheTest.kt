@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.providers.common

import io.karandash.api.ModuleRegistryImpl
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.ProviderResponseCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The run provides its response cache under `ProviderHttpClientFactory.RESPONSE_CACHE`, so provider clients
 * must read that run-scoped key, not only the process-global cache.
 */
class RunResponseCacheTest {
    private class ItemsClient(
        httpClient: HttpClient,
    ) : BaseProviderClient("https://example.com", httpClient) {
        override val platformName: String = "Test"

        override fun credential(): HttpAuth? = null

        suspend fun items(): List<Int> = getAllPages("$baseUrl/items", "items")
    }

    @Test
    fun `a provider client uses the response cache of the run that calls it`() =
        runTest {
            val requests = mutableListOf<String>()
            val engine = MockEngine { request ->
                requests += request.url.toString()
                respond("[1]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = ItemsClient(HttpClient(engine))
            val session = ModuleRegistryImpl().forRun()
            session.provide(ProviderHttpClientFactory.RESPONSE_CACHE, ProviderResponseCache())

            withContext(session.services) {
                assertEquals(listOf(1), client.items())
                assertEquals(listOf(1), client.items())
            }
            assertEquals(1, requests.size, requests.toString())

            client.items()
            assertEquals(2, requests.size, "outside the run there is no cache")
            client.close()
        }
}
