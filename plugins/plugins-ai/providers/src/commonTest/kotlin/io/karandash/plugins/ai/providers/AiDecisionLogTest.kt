package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.karandash.sdk.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runtime AI decisions (per-call stats, fallback) are logged at debug, never with prompt text. */
class AiDecisionLogTest {
    private class CapturingLogger : Logger {
        val lines = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            lines += "DEBUG $message"
        }

        override fun info(message: String) {
            lines += "INFO $message"
        }

        override fun warn(message: String) {
            lines += "WARN $message"
        }

        override fun error(message: String) {
            lines += "ERROR $message"
        }
    }

    private class LoggingContext(
        override val logger: Logger,
    ) : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"

        override fun env(name: String): String? = null
    }

    /** Fails [failModels] (retryable) and [fatalModels] (non-retryable), answers the rest. */
    private class FakeProvider(
        private val failModels: Set<String> = emptySet(),
        private val fatalModels: Set<String> = emptySet(),
    ) : AiProvider {
        override val providerId: String = "fake"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            if (model in fatalModels) throw AiProviderException("auth rejected", statusCode = 401, retryable = false)
            if (model in failModels) throw AiProviderException("server busy", statusCode = 503, retryable = true)
            return AiResponse("ok", promptTokens = 1, completionTokens = 1, totalTokens = 2, model = model, durationMs = 1)
        }

        override suspend fun close() = Unit
    }

    private val okBody =
        """{"id":"c","object":"chat.completion","model":"gpt-x-2026","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
        """.trimMargin()

    @Test
    fun `provider call logs model tokens and latency at info without prompt text`() =
        runTest {
            val log = CapturingLogger()
            val engine = MockEngine { _ -> respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val provider = OpenAiProvider(
                apiKey = "sk-secret-key",
                baseUrl = "https://ai.test/v1",
                timeout = KarandashDuration.ofSeconds(5),
                maxRetries = 0,
                httpClientEngine = engine,
            )
            assertTrue(provider.initialize(LoggingContext(log)))

            provider.complete(PROMPT, SYSTEM_PROMPT, "gpt-x")
            provider.shutdown()

            val call = log.lines.single { "AI call:" in it }
            assertTrue(call.startsWith("INFO AI call: provider=openai model=gpt-x-2026 tokens=11+7=18 latency="), call)
            assertTrue(call.endsWith("ms"), call)
            assertNoSecrets(log)
        }

    @Test
    fun `fallback logs the failed model with its reason and the model that answered`() =
        runTest {
            val log = CapturingLogger()
            val provider = FallbackAiProvider(FakeProvider(failModels = setOf("a")), listOf("b"), log) { _, _ -> }

            provider.complete(PROMPT, SYSTEM_PROMPT, "a")

            assertTrue("INFO [ai] fallback: model a failed (server busy), trying b" in log.lines, log.lines.toString())
            assertTrue("INFO [ai] fallback: model b answered after 1 failed model(s)" in log.lines, log.lines.toString())
            assertNoSecrets(log)
        }

    @Test
    fun `fallback logs why a non-retryable error stops the chain`() =
        runTest {
            val log = CapturingLogger()
            val provider = FallbackAiProvider(FakeProvider(fatalModels = setOf("a")), listOf("b"), log) { _, _ -> }

            assertFailsWith<AiProviderException> { provider.complete(PROMPT, SYSTEM_PROMPT, "a") }

            assertTrue("DEBUG AI fallback: model a failed non-retryable (auth rejected), chain stopped" in log.lines, log.lines.toString())
        }

    private fun assertNoSecrets(log: CapturingLogger) {
        val leaked = log.lines.filter { PROMPT in it || SYSTEM_PROMPT in it || "sk-secret-key" in it }
        assertTrue(leaked.isEmpty(), leaked.toString())
    }

    private companion object {
        const val PROMPT = "PROMPT-TEXT-diff-body"
        const val SYSTEM_PROMPT = "SYSTEM-PROMPT-TEXT"
    }

    @Test
    fun `a 429 retry waits the server Retry-After instead of the backoff`() =
        runTest {
            val log = CapturingLogger()
            var calls = 0
            val engine = MockEngine { _ ->
                if (calls++ == 0) {
                    respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
                } else {
                    respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            val provider = OpenAiProvider(
                apiKey = "sk-secret-key",
                baseUrl = "https://ai.test/v1",
                timeout = KarandashDuration.ofSeconds(5),
                maxRetries = 1,
                httpClientEngine = engine,
            )
            assertTrue(provider.initialize(LoggingContext(log)))

            provider.complete(PROMPT, SYSTEM_PROMPT, "gpt-x")
            provider.shutdown()

            assertTrue(
                log.lines.any {
                    it.startsWith("WARN AI provider: attempt 1/2 failed") && it.endsWith("retrying in 7000ms")
                },
                log.lines.toString()
            )
        }

    @Test
    fun `Retry-After is capped at one minute`() =
        runTest {
            val log = CapturingLogger()
            var calls = 0
            val engine = MockEngine { _ ->
                if (calls++ == 0) {
                    respond("{}", HttpStatusCode.TooManyRequests, headersOf("retry-after-ms", "3600000"))
                } else {
                    respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            val provider = OpenAiProvider(apiKey = "k", baseUrl = "https://ai.test/v1", maxRetries = 1, httpClientEngine = engine)
            assertTrue(provider.initialize(LoggingContext(log)))

            provider.complete(PROMPT, SYSTEM_PROMPT, "gpt-x")
            provider.shutdown()

            assertTrue(log.lines.any { it.endsWith("retrying in 60000ms") }, log.lines.toString())
        }

    @Test
    fun `a 429 is sent ai maxRetries plus one times and never retried by the client too`() =
        runTest {
            val engine = MockEngine { _ -> respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1")) }
            val provider = OpenAiProvider(apiKey = "k", baseUrl = "https://ai.test/v1", maxRetries = 2, httpClientEngine = engine)
            assertTrue(provider.initialize(LoggingContext(CapturingLogger())))

            assertFailsWith<AiProviderException> { provider.complete(PROMPT, SYSTEM_PROMPT, "gpt-x") }
            provider.shutdown()

            assertEquals(3, engine.requestHistory.size)
        }
}
