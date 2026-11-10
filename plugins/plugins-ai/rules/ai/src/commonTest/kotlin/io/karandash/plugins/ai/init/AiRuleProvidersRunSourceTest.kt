@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.init

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiServiceKeys
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.providers.AiProviderResolver
import io.karandash.plugins.ai.providers.CachingAiProvider
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.plugins.ai.providers.RunAiProviderSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Inside a run the AI rule takes its base providers from the run's [RunAiProviderSource]. */
class AiRuleProvidersRunSourceTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private val config = JsonObject(
        mapOf(
            "provider" to JsonPrimitive("openai"),
            "apiKey" to JsonPrimitive("run-key"),
            "baseUrl" to JsonPrimitive("https://run.test/v1"),
            "model" to JsonPrimitive("m"),
        ),
    )

    @Test
    fun `rule providers and analyzer service use the run handles configured from the run ai config`() =
        runTest {
            val engine = MockEngine { _ -> respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val registry = ModuleRegistryImpl().also {
                it.register(
                    OpenAiProvider(
                        apiKey = null,
                        baseUrl = "https://shared.test/v1",
                        timeout = KarandashDuration.ofSeconds(30),
                        maxRetries = 0,
                        httpClientEngine = engine,
                    ),
                )
            }
            val session = registry.forRun()
            val source = RunAiProviderSource(session, registry) { FakeContext(it) }
            session.provide(AiServiceKeys.AI_CONFIG, config)
            session.provide(AiServiceKeys.PROVIDER_SOURCE, source)

            val providers = withContext(session.services) {
                AiRuleProviders(registry, AiProviderResolver(registry))
                    .build(FakeContext(config), AiPluginConfig.fromPluginConfig(config), apiKey = "run-key")
            }

            val built = assertNotNull(providers)
            assertIs<CachingAiProvider>(built.provider)
            built.provider.complete("rule", null, "m", 10, 0.0, false)
            built.aiService.complete(AiCompletionRequest(prompt = "analyzer", model = "m"))
            // The rule closing its chain must not close the run-owned handle.
            built.provider.close()
            assertNotNull(source.provider(null)).complete(AiCompletionRequest(prompt = "after close", model = "m"))

            assertEquals(3, engine.requestHistory.size)
            assertTrue(engine.requestHistory.all { it.url.host == "run.test" })
            assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Authorization] == "Bearer run-key" })
            session.close()
        }

    private class FakeContext(
        override val pluginConfig: JsonElement,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
