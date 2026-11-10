package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.buildTestRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** code-suggestions, code-quality and fix-suggestions run as their own analyzers, next to review-comments. */
class AiProducerRoutingTest {
    private suspend fun enabled(
        features: Map<String, Any?>,
        warnings: MutableList<String> = mutableListOf(),
    ): Set<String>? {
        val context = object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model", "features" to features),
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
        return AiRuleInitializer(buildTestRegistry(), null).initialize(context)?.enabledRules
    }

    @Test
    fun `code-quality and fix-suggestions run as themselves`() =
        runTest {
            val warnings = mutableListOf<String>()

            val rules = enabled(mapOf("riskScoring" to true, "fixSuggestions" to true, "codeQuality" to true), warnings)

            assertEquals(setOf("risk-scoring", "fix-suggestions", "code-quality"), rules)
            assertTrue(warnings.none { "review-comments" in it && "now run" in it }, warnings.toString())
        }

    @Test
    fun `an allowlist of codeSuggestions alone runs code-suggestions`() =
        runTest {
            assertEquals(setOf("code-suggestions"), enabled(mapOf("codeSuggestions" to true)))
        }

    @Test
    fun `review-comments enabled on its own runs only review-comments`() =
        runTest {
            assertEquals(setOf("review-comments"), enabled(mapOf("reviewComments" to true)))
        }

    @Test
    fun `code-suggestions runs when reviewComments is false`() =
        runTest {
            val rules = enabled(mapOf("riskScoring" to true, "codeSuggestions" to true, "reviewComments" to false))

            assertEquals(setOf("risk-scoring", "code-suggestions"), rules)
        }

    @Test
    fun `an allowlist names the default analyzers it turns off`() =
        runTest {
            val warnings = mutableListOf<String>()

            assertEquals(setOf("risk-scoring"), enabled(mapOf("riskScoring" to true), warnings))
            val warning = warnings.singleOrNull { "turned off" in it }
            assertTrue(warning != null && "review-comments" in warning && "risk-scoring" !in warning, warnings.toString())
        }

    @Test
    fun `no allowlist warning when every default stays on`() =
        runTest {
            val warnings = mutableListOf<String>()

            enabled(mapOf("riskScoring" to true, "reviewComments" to true), warnings)
            enabled(mapOf("riskScoring" to false), warnings)
            assertTrue(warnings.none { "turned off" in it }, warnings.toString())
        }

    @Test
    fun `the default rule set is risk-scoring and review-comments`() =
        runTest {
            val warnings = mutableListOf<String>()

            assertEquals(setOf("risk-scoring", "review-comments"), enabled(emptyMap(), warnings))
            assertNull(warnings.firstOrNull { "deprecated" in it })
        }
}
