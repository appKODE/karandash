package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.buildTestRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Misconfigured `providers:` entries must be reported, not silently dropped. */
class AiRuleInitializerProvidersWarnTest {
    private fun contextWith(
        providers: Map<String, Any?>,
        warnings: MutableList<String>,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "providers" to providers,
                ),
            )
            override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {
                warnings += message
            }

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `object-valued providers entry is warned about`() =
        runTest {
            val warnings = mutableListOf<String>()
            val context = contextWith(mapOf("fast" to mapOf("type" to "openai")), warnings)

            val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context)

            assertNotNull(result)
            assertTrue(warnings.any { "fast" in it }, "expected a warning naming 'fast', got $warnings")
        }

    @Test
    fun `unresolvable provider type is warned about`() =
        runTest {
            val warnings = mutableListOf<String>()
            val context = contextWith(mapOf("slow" to "no-such-provider"), warnings)

            val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context)

            assertNotNull(result)
            assertTrue(
                warnings.any { "slow" in it && "no-such-provider" in it },
                "expected a warning naming 'slow' and its type, got $warnings",
            )
        }
}
