package io.karandash.http

import io.karandash.duration.KarandashDuration
import io.karandash.http.ProviderHttpClientFactory.applyRetry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** idempotent requests retry twice by default, and a 429 is retried after its (capped) `Retry-After`. */
class ProviderRetryDefaultsTest {
    private fun engine(
        calls: MutableList<String>,
        first: HttpStatusCode,
        retryAfter: String? = null,
        always: Boolean = false,
    ) = MockEngine { request ->
        calls += request.method.value
        if (always || calls.size == 1) {
            val headers = retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf()
            respond("", first, headers)
        } else {
            respond("ok", HttpStatusCode.OK)
        }
    }

    private fun recordingClient(
        engine: MockEngine,
        delays: MutableList<Long>,
    ) = HttpClient(engine) {
        install(HttpRequestRetry) {
            applyRetry(HttpClientSettings.Retry(2, KarandashDuration.ofMillis(1)))
            delay { delays += it }
        }
    }

    @Test
    fun `an idempotent GET is retried twice without an http retry block`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = engine(calls, HttpStatusCode.ServiceUnavailable, always = true))
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("https://example.com/a").status)
            assertEquals(listOf("GET", "GET", "GET"), calls)
            client.close()
        }

    @Test
    fun `a 429 GET is retried after its Retry-After delay`() =
        runTest {
            val calls = mutableListOf<String>()
            val delays = mutableListOf<Long>()
            val client = recordingClient(engine(calls, HttpStatusCode.TooManyRequests, retryAfter = "3"), delays)
            assertEquals(HttpStatusCode.OK, client.get("https://example.com/a").status)
            assertEquals(listOf("GET", "GET"), calls)
            assertEquals(listOf(3_000L), delays)
            client.close()
        }

    @Test
    fun `a huge Retry-After is capped`() =
        runTest {
            val delays = mutableListOf<Long>()
            val client = recordingClient(engine(mutableListOf(), HttpStatusCode.TooManyRequests, retryAfter = "86400"), delays)
            assertEquals(HttpStatusCode.OK, client.get("https://example.com/a").status)
            assertEquals(listOf(ProviderHttpClientFactory.MAX_RETRY_AFTER_MS), delays)
            client.close()
        }

    @Test
    fun `a POST that got a 429 is not retried`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = engine(calls, HttpStatusCode.TooManyRequests, retryAfter = "1"))
            assertEquals(HttpStatusCode.TooManyRequests, client.post("https://example.com/a").status)
            assertEquals(listOf("POST"), calls)
            client.close()
        }

    @Test
    fun `a POST that failed in transit is not retried`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(
                engine = MockEngine { request ->
                    calls += request.method.value
                    throw IOException("connection reset")
                },
            )
            assertFailsWith<IOException> { client.post("https://example.com/a") }
            assertEquals(listOf("POST"), calls)
            client.close()
        }

    @Test
    fun `retryUnsentPost retries a POST on a 429 or a connect timeout but not on a 5xx or a reset`() =
        runTest {
            val tooMany = mutableListOf<String>()
            ProviderHttpClientFactory.create(engine = engine(tooMany, HttpStatusCode.TooManyRequests), retryUnsentPost = true).use {
                assertEquals(HttpStatusCode.OK, it.post("https://example.com/a").status)
            }
            assertEquals(listOf("POST", "POST"), tooMany)

            val serverError = mutableListOf<String>()
            ProviderHttpClientFactory.create(engine = engine(serverError, HttpStatusCode.BadGateway), retryUnsentPost = true).use {
                assertEquals(HttpStatusCode.BadGateway, it.post("https://example.com/a").status)
            }
            assertEquals(listOf("POST"), serverError)

            listOf(ConnectTimeoutException("connect timed out") to 3, IOException("connection reset") to 1).forEach { (failure, attempts) ->
                val calls = mutableListOf<String>()
                val engine = MockEngine { request ->
                    calls += request.method.value
                    throw failure
                }
                ProviderHttpClientFactory.create(engine = engine, retryUnsentPost = true).use {
                    assertFailsWith<IOException> { it.post("https://example.com/a") }
                }
                assertEquals(attempts, calls.size, failure.message)
            }
        }
}
