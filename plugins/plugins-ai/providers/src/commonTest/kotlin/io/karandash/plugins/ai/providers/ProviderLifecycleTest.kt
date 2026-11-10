@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpClientLedger
import io.karandash.http.ProviderHttpClientFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Behavior-level guarantees for provider client lifecycle and cancellation.
 * Deliberately assert observable outcomes (request succeeds / times out / is not retried),
 * not internals, so they hold across later restructuring of client ownership.
 */
class ProviderLifecycleTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private fun slowOkEngine(delayMs: Long): HttpClientEngine =
        MockEngine { _ ->
            delay(delayMs)
            respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private fun openAi(
        engine: HttpClientEngine,
        timeout: KarandashDuration,
        maxRetries: Int = 0,
    ) = OpenAiProvider(
        apiKey = "k",
        baseUrl = "https://api.openai.com/v1",
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = engine,
    )

    @Test
    fun `configured shorter timeout is enforced after close and rebuild`() =
        runTest {
            withContext(Dispatchers.Default) {
                val provider = openAi(slowOkEngine(600), KarandashDuration.ofSeconds(30))
                provider.configure(apiKey = null, baseUrl = null, timeout = KarandashDuration.ofMillis(150))
                provider.close()

                val ex = assertFailsWith<AiProviderException> { provider.complete(prompt = "hi") }
                assertTrue(ex.message.orEmpty().contains("timed out"), "was: ${ex.message}")
                provider.close()
            }
        }

    @Test
    fun `configured longer timeout is honoured after close and rebuild`() =
        runTest {
            withContext(Dispatchers.Default) {
                val provider = openAi(slowOkEngine(300), KarandashDuration.ofMillis(100))
                provider.configure(apiKey = null, baseUrl = null, timeout = KarandashDuration.ofSeconds(30))
                provider.close()

                assertEquals("ok", provider.complete(prompt = "hi").content)
                provider.close()
            }
        }

    @Test
    fun `provider is reusable after close`() =
        runTest {
            val provider = openAi(slowOkEngine(0), KarandashDuration.ofSeconds(5))
            assertEquals("ok", provider.complete(prompt = "a").content)
            provider.close()
            assertEquals("ok", provider.complete(prompt = "b").content)
            provider.close()
        }

    @Test
    fun `parallel requests after close create exactly one client`() =
        runTest {
            val ledger = HttpClientLedger()
            ProviderHttpClientFactory.installLedger(ledger)
            try {
                val provider = openAi(slowOkEngine(0), KarandashDuration.ofSeconds(5))
                provider.complete(prompt = "warm")
                provider.close()
                val before = ledger.createdCount

                val results = (1..8).map { async { provider.complete(prompt = "p$it").content } }.awaitAll()

                assertEquals(List(8) { "ok" }, results)
                assertEquals(1, ledger.createdCount - before)
                provider.close()
            } finally {
                ProviderHttpClientFactory.clearLedger(ledger)
            }
        }

    @Test
    fun `concurrent requests after close publish a single shared client`() =
        runTest {
            val ledger = HttpClientLedger()
            ProviderHttpClientFactory.installLedger(ledger)
            try {
                withContext(Dispatchers.Default) {
                    val provider = openAi(slowOkEngine(0), KarandashDuration.ofSeconds(5))
                    provider.complete(prompt = "warm")
                    provider.close()

                    val gate = CompletableDeferred<Unit>()
                    val burst = (1..CONCURRENT_REQUESTS).map { n ->
                        async(Dispatchers.Default) {
                            gate.await()
                            provider.complete(prompt = "p$n").content
                        }
                    }
                    val before = ledger.createdCount
                    gate.complete(Unit)

                    assertEquals(List(CONCURRENT_REQUESTS) { "ok" }, burst.awaitAll())
                    // Racing callers may build a spare client, but spares must be discarded:
                    // once the burst settled, one published client serves everything.
                    assertTrue(ledger.createdCount - before >= 1, "burst must have built a client")
                    val afterBurst = ledger.createdCount

                    repeat(CONCURRENT_REQUESTS) { provider.complete(prompt = "again") }
                    assertEquals(afterBurst, ledger.createdCount, "a live client must be reused, not rebuilt")

                    provider.close()
                    provider.complete(prompt = "after final close")
                    assertEquals(afterBurst + 1, ledger.createdCount, "close() must drop the single published client")
                    provider.close()
                }
            } finally {
                ProviderHttpClientFactory.clearLedger(ledger)
            }
        }

    @Test
    fun `OpenAI request is not retried when the engine throws CancellationException`() =
        assertCancellationPropagatesWithoutRetry { engine, retries ->
            openAi(engine, KarandashDuration.ofSeconds(30), retries)
        }

    @Test
    fun `Anthropic request is not retried when the engine throws CancellationException`() =
        assertCancellationPropagatesWithoutRetry { engine, retries ->
            AnthropicProvider(
                apiKey = "k",
                baseUrl = "https://api.anthropic.com/v1",
                timeout = KarandashDuration.ofSeconds(30),
                maxRetries = retries,
                httpClientEngine = engine,
            )
        }

    @Test
    fun `Ollama request surfaces CancellationException when the engine throws it`() =
        assertCancellationPropagatesWithoutRetry { engine, retries ->
            OllamaProvider(
                baseUrl = "http://ollama:11434/v1",
                timeout = KarandashDuration.ofSeconds(30),
                maxRetries = retries,
                httpClientEngine = engine,
            )
        }

    /**
     * The calling coroutine is NOT cancelled; only the engine throws [CancellationException].
     * A provider that wraps it into [AiProviderException] (and retries) is wrong.
     */
    private fun assertCancellationPropagatesWithoutRetry(create: (HttpClientEngine, Int) -> AiProvider) =
        runTest {
            val calls = MutableStateFlow(0)
            val engine = MockEngine { _ ->
                calls.update { it + 1 }
                throw CancellationException("engine cancelled")
            }
            val provider = create(engine, 3)

            assertFailsWith<CancellationException> { provider.complete(prompt = "hi") }
            assertEquals(1, calls.value, "cancellation must not be retried")
        }

    private companion object {
        const val CONCURRENT_REQUESTS = 64
    }
}
