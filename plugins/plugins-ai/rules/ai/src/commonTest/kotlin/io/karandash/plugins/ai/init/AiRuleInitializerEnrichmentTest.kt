package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.buildTestRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies [AiRuleInitializer.initialize] captures [PluginContext.enrichment] into
 * [InitResult.enrichment], so AI-dispatched rules can see enrichment data
 * through `rule-enrichment:` config bindings.
 */
class AiRuleInitializerEnrichmentTest {
    private fun createContext(
        pluginConfig: Map<String, Any?>,
        enrichmentContext: EnrichmentContext,
    ): PluginContext = createContextFromElement(mapToJsonElement(pluginConfig), enrichmentContext)

    private fun createContextFromElement(
        pluginConfig: JsonElement,
        enrichmentContext: EnrichmentContext,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = pluginConfig
            override val enrichment: EnrichmentContext = enrichmentContext

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `initialize captures enrichment context into InitResult`() =
        runTest {
            val ticketsJson = JsonPrimitive("stub-tickets-payload")
            val enrichmentContext = EnrichmentContext(data = mapOf("jira:tickets" to ticketsJson))
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                ),
                enrichmentContext = enrichmentContext,
            )

            val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context)

            assertNotNull(result, "Expected successful initialization")
            assertEquals(ticketsJson, result.enrichment.get("jira:tickets"))
        }

    @Test
    fun `initialize defaults to empty enrichment when context provides none`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                ),
                enrichmentContext = EnrichmentContext.EMPTY,
            )

            val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context)

            assertNotNull(result)
            assertEquals(emptySet(), result.enrichment.availableIds)
        }

    /**
     * Verifies [AiRuleInitializer.initialize] carries the [ruleEnrichmentBindingPrefixes]
     * parameter straight through into [InitResult.ruleEnrichmentBindingPrefixes] -- the
     * direct-parameter channel that replaced the old JSON-config smuggling mechanism.
     */
    @Test
    fun `ruleEnrichmentBindingPrefixes parameter round trips into InitResult`() =
        runTest {
            val bindings = mapOf(
                "review-comments" to listOf("gitlab:"),
                "ticket-compliance" to listOf("confluence:"),
            )
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                ),
                enrichmentContext = EnrichmentContext.EMPTY,
            )

            val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context, bindings)

            assertNotNull(result, "Expected successful initialization")
            assertEquals(bindings, result.ruleEnrichmentBindingPrefixes)
        }
}
