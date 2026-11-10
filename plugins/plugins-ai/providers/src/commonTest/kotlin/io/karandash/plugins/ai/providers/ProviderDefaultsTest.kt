package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Provider alignment: one base-URL order, shared defaults, bad config handling and the refusal warning. */
class ProviderDefaultsTest {
    private class Ctx(
        override val pluginConfig: JsonElement = JsonObject(emptyMap()),
        private val envVars: Map<String, String> = emptyMap(),
    ) : PluginContext {
        val recording = RecordingLogger()
        override val logger get() = recording
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"

        override fun env(name: String): String? = envVars[name]
    }

    private fun ok(content: String = "\"ok\"") =
        """{"id":"c","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":$content},
            |"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private fun engine(body: String = ok()) =
        MockEngine { _ -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private val timeout = KarandashDuration.ofSeconds(5)

    @Test
    fun `OPENAI_BASE_URL replaces the default base URL`() =
        runTest {
            val engine = engine()
            val provider = OpenAiProvider(apiKey = "k", timeout = timeout, maxRetries = 0, httpClientEngine = engine)
            assertTrue(provider.initialize(Ctx(envVars = mapOf(AiEnvVars.OPENAI_BASE_URL to "https://env.test/v1"))))
            provider.complete(prompt = "hi")
            assertEquals(
                "env.test",
                engine.requestHistory
                    .single()
                    .url.host
            )
        }

    @Test
    fun `ai baseUrl wins over the env var`() =
        runTest {
            val engine = engine(
                """{"id":"m","type":"message","role":"assistant","model":"m","stop_reason":"end_turn",
                    |"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}
                """.trimMargin(),
            )
            val provider = AnthropicProvider(apiKey = "k", timeout = timeout, maxRetries = 0, httpClientEngine = engine)
            provider.configure(apiKey = null, baseUrl = "https://config.test/v1")
            assertTrue(provider.initialize(Ctx(envVars = mapOf(AiEnvVars.ANTHROPIC_BASE_URL to "https://env.test/v1"))))
            provider.complete(prompt = "hi")
            assertEquals(
                "config.test",
                engine.requestHistory
                    .single()
                    .url.host
            )
        }

    @Test
    fun `BIFROST_BASE_URL wins over the constructor value`() =
        runTest {
            val engine = engine()
            val provider = BifrostProvider(
                bifrostApiKey = "k",
                baseUrl = "https://constructor.test/v1",
                httpClientEngine = engine,
            )
            assertTrue(provider.initialize(Ctx(envVars = mapOf(AiEnvVars.BIFROST_BASE_URL to "https://env.test/v1"))))
            provider.complete(prompt = "hi")
            assertEquals(
                "env.test",
                engine.requestHistory
                    .single()
                    .url.host
            )
        }

    @Test
    fun `a config that does not decode disables every provider with a warning`() =
        runTest {
            val bad = JsonObject(mapOf("maxRetries" to JsonPrimitive("many")))
            listOf(
                OpenAiProvider(apiKey = "k", httpClientEngine = engine()),
                AnthropicProvider(apiKey = "k", httpClientEngine = engine()),
                OllamaProvider(httpClientEngine = engine()),
            ).forEach { provider ->
                val ctx = Ctx(pluginConfig = bad)
                assertFalse(provider.initialize(ctx), provider.providerId)
                assertTrue(ctx.recording.lines.any { it.startsWith("WARN") && "invalid config" in it }, provider.providerId)
            }
        }

    @Test
    fun `openai-compatible default timeout is 60 seconds`() {
        assertEquals(KarandashDuration.ofSeconds(60), OpenAiProvider.DEFAULT_TIMEOUT)
    }

    @Test
    fun `openai refusal is logged as a warning`() =
        runTest {
            val body =
                """{"id":"c","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":null,
                    |"refusal":"I cannot help with that"},"finish_reason":"stop"}],
                    |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.trimMargin()
            val provider = OpenAiProvider(apiKey = "k", timeout = timeout, maxRetries = 0, httpClientEngine = engine(body))
            val ctx = Ctx()
            assertTrue(provider.initialize(ctx))
            assertEquals("", provider.complete(prompt = "hi").content)
            assertTrue(ctx.recording.lines.any { it.startsWith("WARN") && "refused the request: I cannot help with that" in it })
        }
}
