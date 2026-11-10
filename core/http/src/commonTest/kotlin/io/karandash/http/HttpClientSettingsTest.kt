@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.http

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.run.RunScopedServices
import io.karandash.duration.KarandashDuration
import io.karandash.pagination.DEFAULT_MAX_PAGES
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByPageNumber
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpClientSettingsTest {
    private val retrying = HttpClientSettings(retry = HttpClientSettings.Retry(2, KarandashDuration.ofMillis(1)))
    private val noRetry = HttpClientSettings(retry = HttpClientSettings.Retry(0, KarandashDuration.ofMillis(1)))

    private fun flakyEngine(calls: MutableList<String>) =
        MockEngine { request ->
            calls += request.method.value
            if (calls.size == 1) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
        }

    private suspend fun run(settings: HttpClientSettings): RunScopedServices {
        val session = ModuleRegistryImpl().forRun()
        session.provide(ProviderHttpClientFactory.HTTP_SETTINGS, settings)
        return session.services
    }

    @Test
    fun `configured retry repeats a GET that got a 5xx`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = flakyEngine(calls))
            withContext(run(retrying)) { assertEquals(HttpStatusCode.OK, client.get("https://example.com/a").status) }
            assertEquals(listOf("GET", "GET"), calls)
            client.close()
        }

    @Test
    fun `http retry baseDelay is the virtual backoff before the first retry`() =
        runTest {
            suspend fun elapsedWith(baseDelay: KarandashDuration): Long {
                val calls = mutableListOf<String>()
                val client = ProviderHttpClientFactory.create(engine = flakyEngine(calls))
                val settings = HttpClientSettings(retry = HttpClientSettings.Retry(2, baseDelay))
                val start = testScheduler.currentTime
                withContext(run(settings)) { assertEquals(HttpStatusCode.OK, client.get("https://example.com/a").status) }
                assertEquals(listOf("GET", "GET"), calls)
                client.close()
                return testScheduler.currentTime - start
            }

            assertEquals(1L, elapsedWith(KarandashDuration.ofMillis(1)))
            assertEquals(500L, elapsedWith(KarandashDuration.ofMillis(500)))
        }

    @Test
    fun `settings passed as services at creation apply too`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = flakyEngine(calls), services = run(retrying))
            assertEquals(HttpStatusCode.OK, client.get("https://example.com/a").status)
            assertEquals(listOf("GET", "GET"), calls)
            client.close()
        }

    @Test
    fun `configured retry never repeats a POST`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = flakyEngine(calls))
            withContext(run(retrying)) { assertEquals(HttpStatusCode.ServiceUnavailable, client.post("https://example.com/a").status) }
            assertEquals(listOf("POST"), calls)
            client.close()
        }

    @Test
    fun `http retry with zero attempts retries nothing`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = flakyEngine(calls))
            withContext(run(noRetry)) { assertEquals(HttpStatusCode.ServiceUnavailable, client.get("https://example.com/a").status) }
            assertEquals(listOf("GET"), calls)
            client.close()
        }

    /** `applyRetry` is live, `RunSettingsPlugin` applies each run's `http.retry` per request. */
    @Test
    fun `one client follows the retry setting of the run that calls it`() =
        runTest {
            val calls = mutableListOf<String>()
            val failing = MockEngine { request ->
                calls += request.url.encodedPath
                respond("", HttpStatusCode.ServiceUnavailable)
            }
            val client = ProviderHttpClientFactory.create(engine = failing)
            val retryingRun = run(retrying)
            val plainRun = run(noRetry)
            async(retryingRun) { client.get("https://example.com/retrying") }.await()
            async(plainRun) { client.get("https://example.com/plain") }.await()
            assertEquals(listOf("/retrying", "/retrying", "/retrying", "/plain"), calls)
            client.close()
        }

    @Test
    fun `timeouts http is the default request timeout and an explicit timeout wins`() =
        runTest {
            val slow = MockEngine {
                delay(5_000)
                respond("late", HttpStatusCode.OK)
            }
            withContext(run(HttpClientSettings(timeout = KarandashDuration.ofSeconds(1)))) {
                val configured = ProviderHttpClientFactory.create(engine = slow)
                assertFailsWith<HttpRequestTimeoutException> { configured.get("https://example.com/a") }
                configured.close()
                val explicit = ProviderHttpClientFactory.create(engine = slow, timeout = KarandashDuration.ofSeconds(10))
                assertEquals(HttpStatusCode.OK, explicit.get("https://example.com/a").status)
                explicit.close()
            }
        }

    @Test
    fun `http pagination maxPages caps paginators per run and falls back to the client cap`() =
        runTest {
            assertEquals(20, ProviderHttpClientFactory.maxPages(20))
            val capped = run(HttpClientSettings(maxPages = 3))
            val other = run(HttpClientSettings())
            withContext(capped) {
                assertEquals(3, ProviderHttpClientFactory.maxPages(20))
                val pages = mutableListOf<Int>()
                paginateByPageNumber { page ->
                    pages += page
                    PaginationResult(listOf(page), "${page + 1}")
                }
                assertEquals(listOf(1, 2, 3), pages)
            }
            withContext(other) { assertEquals(DEFAULT_MAX_PAGES, ProviderHttpClientFactory.maxPages(DEFAULT_MAX_PAGES)) }
        }
}
