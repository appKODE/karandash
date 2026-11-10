package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.buildTestRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiRuleInitializerBudgetTest {
    private val logs = mutableListOf<String>()

    private fun context(costBudget: String): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = KarandashJson.Default.parseToJsonElement(
                """{"provider":"custom","baseUrl":"http://localhost:1234/v1","model":"test-model",
                   "statistics":{"dir":"build/no-such-stats-dir"},"costBudget":$costBudget}""",
            )
            override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY

            override fun env(name: String): String? = null

            override fun log(message: String) {
                logs += message
            }

            override fun warn(message: String) = Unit

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `adaptiveAllocation logs the per-rule allocation breakdown`() =
        runTest {
            val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context("""{"adaptiveAllocation":true}"""))
            assertNotNull(result)
            assertTrue(result.perRuleBudgets.isNotEmpty())
            assertEquals(1, logs.count { "Adaptive budget allocation" in it })
        }

    @Test
    fun `without adaptiveAllocation no breakdown is logged`() =
        runTest {
            assertNotNull(AiRuleInitializer(buildTestRegistry(), null).initialize(context("{}")))
            assertTrue(logs.none { "Adaptive budget allocation" in it })
        }
}
