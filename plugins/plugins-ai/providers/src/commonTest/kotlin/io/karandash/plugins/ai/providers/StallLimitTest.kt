package io.karandash.plugins.ai.providers

import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A non-streaming call caps the silence between reads, so a stalled connection fails and is retried. */
class StallLimitTest {
    private val body = """{"id":"c","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
        |"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """.trimMargin()

    private suspend fun socketTimeoutOf(
        timeout: KarandashDuration,
        stream: Boolean,
    ): Long? {
        val engine = MockEngine { _ -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val provider = OpenAiProvider(apiKey = "k", timeout = timeout, maxRetries = 0, httpClientEngine = engine)
        provider.configure(apiKey = null, baseUrl = null, stream = stream)
        runCatching { provider.complete(prompt = "hi") }
        return engine.requestHistory
            .first()
            .getCapabilityOrNull(HttpTimeoutCapability)
            ?.socketTimeoutMillis
    }

    @Test
    fun `a long ai timeout is capped to 180s of silence when not streaming`() =
        runTest {
            assertEquals(180_000L, socketTimeoutOf(KarandashDuration.ofMinutes(5), stream = false))
        }

    @Test
    fun `a short ai timeout keeps its own socket timeout`() =
        runTest {
            assertEquals(60_000L, socketTimeoutOf(KarandashDuration.ofSeconds(60), stream = false))
        }

    @Test
    fun `streaming caps the silence between chunks at ai streamStall`() =
        runTest {
            assertEquals(60_000L, socketTimeoutOf(KarandashDuration.ofMinutes(5), stream = true))
            assertEquals(30_000L, socketTimeoutOf(KarandashDuration.ofSeconds(30), stream = true))
        }
}
