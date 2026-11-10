package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.HttpClientEngine
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
import kotlin.test.assertTrue

/**
 * Every provider sends only its own key; `OPENAI_API_KEY` never reaches Ollama or a
 * `custom` endpoint.
 */
class ProviderAuthHeaderTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private val allKeys = mapOf(
        AiEnvVars.OPENAI_API_KEY to "openai-key",
        AiEnvVars.GEMINI_API_KEY to "gemini-key",
        AiEnvVars.LITELLM_API_KEY to "litellm-key",
        AiEnvVars.BIFROST_API_KEY to "bifrost-key",
        AiEnvVars.CUSTOM_AI_API_KEY to "custom-key",
    )

    private fun engine() = MockEngine { respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private suspend fun authHeaderOf(
        env: Map<String, String>,
        build: (HttpClientEngine) -> AbstractAiProvider,
    ): String? {
        val engine = engine()
        val provider = build(engine)
        provider.initialize(EnvContext(env))
        provider.complete(prompt = "hi", model = "m")
        provider.close()
        return engine.requestHistory.single().headers[HttpHeaders.Authorization]
    }

    @Test
    fun `each provider sends the bearer key of its own env var`() =
        runTest {
            val t = KarandashDuration.ofSeconds(5)
            val cases = listOf<Pair<String?, (HttpClientEngine) -> AbstractAiProvider>>(
                "Bearer openai-key" to
                    { e -> OpenAiProvider(baseUrl = "https://api.openai.com/v1", timeout = t, maxRetries = 0, httpClientEngine = e) },
                "Bearer gemini-key" to { e -> GeminiProvider(timeout = t, maxRetries = 0, httpClientEngine = e) },
                "Bearer litellm-key" to
                    { e -> LiteLLMProvider(baseUrl = "https://litellm.test/v1", timeout = t, maxRetries = 0, httpClientEngine = e) },
                "Bearer bifrost-key" to
                    { e -> BifrostProvider(baseUrl = "https://bifrost.test/v1", timeout = t, maxRetries = 0, httpClientEngine = e) },
                "Bearer custom-key" to
                    { e -> CustomAiProvider(baseUrl = "https://llm.test/v1", timeout = t, maxRetries = 0, httpClientEngine = e) },
                null to { e -> OllamaProvider(baseUrl = "http://localhost:11434/v1", timeout = t, maxRetries = 0, httpClientEngine = e) },
            )
            for ((expected, build) in cases) {
                assertEquals(expected, authHeaderOf(allKeys, build), "provider built for $expected")
            }
        }

    @Test
    fun `OPENAI_API_KEY is never sent to ollama or a custom endpoint`() =
        runTest {
            val t = KarandashDuration.ofSeconds(5)
            val env = mapOf(AiEnvVars.OPENAI_API_KEY to "openai-key")
            val ollama =
                authHeaderOf(
                    env
                ) { e -> OllamaProvider(baseUrl = "http://localhost:11434/v1", timeout = t, maxRetries = 0, httpClientEngine = e) }
            val custom =
                authHeaderOf(
                    env
                ) { e -> CustomAiProvider(baseUrl = "https://llm.test/v1", timeout = t, maxRetries = 0, httpClientEngine = e) }
            assertEquals(null, ollama)
            assertEquals(null, custom)
        }

    @Test
    fun `ollama with structured output sends no OPENAI_API_KEY`() =
        runTest {
            val engine = engine()
            val provider =
                OllamaProvider(
                    baseUrl = "http://localhost:11434/v1",
                    timeout = KarandashDuration.ofSeconds(5),
                    maxRetries = 0,
                    httpClientEngine = engine
                )
            provider.configure(apiKey = null, baseUrl = null, structuredOutput = true)
            provider.initialize(EnvContext(mapOf(AiEnvVars.OPENAI_API_KEY to "openai-key")))
            provider.completeRequest(
                io.karandash.api.ai.AiCompletionRequest(
                    prompt = "hi",
                    model = "m",
                    responseSchema = JsonObject(emptyMap()),
                ),
            )
            assertTrue(engine.requestHistory.isNotEmpty())
            assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Authorization] == null })
        }

    private class EnvContext(
        private val envVars: Map<String, String>,
        override val pluginConfig: JsonElement = JsonObject(emptyMap()),
        override val logger: Logger = NoOpLogger,
    ) : PluginContext {
        override fun env(name: String): String? = envVars[name]

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
