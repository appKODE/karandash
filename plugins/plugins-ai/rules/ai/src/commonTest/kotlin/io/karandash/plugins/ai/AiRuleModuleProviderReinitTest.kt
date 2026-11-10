package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-initializing [AiRuleModule] closes the previous run's providers. A registry-owned provider
 * must stay usable (with its configured timeout) afterwards, because the registry hands out the same instance.
 */
class AiRuleModuleProviderReinitTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private class Fixture(
        val provider: OpenAiProvider,
        val registry: ModuleRegistry,
        val requests: MutableStateFlow<Int>,
    )

    private fun fixture(
        engineDelayMs: Long,
        ctorTimeout: KarandashDuration,
    ): Fixture {
        val requests = MutableStateFlow(0)
        val engine = MockEngine { _ ->
            requests.update { it + 1 }
            delay(engineDelayMs)
            respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val provider = OpenAiProvider(
            apiKey = "k",
            timeout = ctorTimeout,
            maxRetries = 0,
            httpClientEngine = engine,
        )
        val base = buildTestRegistry()
        val registry = object : ModuleRegistry by base {
            override fun aiProviders(): List<AiProviderModule> = listOf(provider)

            override fun findAiProvider(providerId: String): AiProviderModule? = provider.takeIf { it.providerId == providerId }
        }
        return Fixture(provider, registry, requests)
    }

    private fun context(
        config: Map<String, Any?>,
        enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val enrichment: EnrichmentContext = enrichment

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `main and same-type named provider both serve requests after initialize runs twice`() =
        runTest {
            withContext(Dispatchers.Default) {
                val f = fixture(engineDelayMs = 0, ctorTimeout = KarandashDuration.ofSeconds(5))
                val ctx = context(
                    mapOf("provider" to "openai", "apiKey" to "k", "model" to "gpt-4o-mini", "providers" to mapOf("fast" to "openai")),
                )
                val module = AiRuleModule(f.registry)

                assertTrue(module.initialize(ctx))
                assertEquals("ok", f.provider.complete(prompt = "first run").content)
                assertTrue(module.initialize(ctx))

                assertEquals("ok", f.provider.complete(prompt = "main").content)
                assertEquals("ok", f.provider.complete(prompt = "named").content)
                assertEquals(3, f.requests.value)
            }
        }

    @Test
    fun `configured ai timeout is still enforced after re-initialize with enrichment`() =
        runTest {
            withContext(Dispatchers.Default) {
                // Constructor default (100ms) is shorter than the engine latency (400ms); only the configured 30s lets it pass.
                val f = fixture(engineDelayMs = 400, ctorTimeout = KarandashDuration.ofMillis(100))
                val config = mapOf("provider" to "openai", "apiKey" to "k", "model" to "gpt-4o-mini", "timeout" to "30s")
                val module = AiRuleModule(f.registry)

                assertTrue(module.initialize(context(config)))
                val enriched = EnrichmentContext(data = mapOf("jira:tickets" to JsonPrimitive("payload")))
                assertTrue(module.initialize(context(config, enriched)))

                assertEquals("ok", f.provider.complete(prompt = "hi").content)
            }
        }
}
