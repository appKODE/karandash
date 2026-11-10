package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [AiRuleModule.ruleScopedEnrichment] delivers TRUE per-rule enrichment
 * isolation rather than a module-wide fallback.
 *
 * Two independent sources feed a rule's entitlement:
 *  - its own analyzer's [AiAnalyzerModule.supportedEnrichment] declaration, and
 *  - a `rule-enrichment:` config binding for that specific rule ID (delivered via
 *    [AiRuleInitState.ruleEnrichmentBindingPrefixes]).
 *
 * A rule with neither source must not fall back to the module-wide [EnrichmentContext]
 * (the union of every other rule's data), or data leaks across rules that share one
 * [AiRuleModule] instance.
 */
class AiRuleModulePerRuleEnrichmentTest {
    private fun fakeAnalyzer(
        analyzerIdValue: String,
        interest: List<String> = emptyList()
    ): AiAnalyzerModule =
        object : AiAnalyzerModule {
            override val id: String = analyzerIdValue
            override val name: String = analyzerIdValue
            override val version: String = "test"
            override val type: ModuleType get() = ModuleType.AI_ANALYZER
            override val analyzerId: String = analyzerIdValue
            override val supportedEnrichment: List<String> = interest

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
        }

    private fun registryWithAnalyzers(analyzers: List<AiAnalyzerModule>): ModuleRegistry =
        object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }
        }

    private val moduleEnrichment = EnrichmentContext(
        data = mapOf(
            "gitlab:mr" to JsonPrimitive("gitlab-data"),
            "ci:pipeline" to JsonPrimitive("ci-data"),
            "confluence:page" to JsonPrimitive("confluence-data"),
        ),
    )

    @Test
    fun `rule bound via config sees only its own bound prefixes and not a sibling rule's`() {
        val registry = registryWithAnalyzers(
            listOf(fakeAnalyzer("review-comments"), fakeAnalyzer("code-suggestions")),
        )
        val module = AiRuleModule(registry)
        val bindings = mapOf(
            "review-comments" to listOf("gitlab:"),
            "code-suggestions" to listOf("ci:"),
        )

        val reviewComments = module.ruleScopedEnrichment("review-comments", moduleEnrichment, bindings)
        val codeSuggestions = module.ruleScopedEnrichment("code-suggestions", moduleEnrichment, bindings)

        assertEquals(setOf("gitlab:mr"), reviewComments.availableIds)
        assertEquals(setOf("ci:pipeline"), codeSuggestions.availableIds)
        assertTrue("confluence:page" !in reviewComments.availableIds && "confluence:page" !in codeSuggestions.availableIds)
    }

    @Test
    fun `rule with no analyzer interest and no binding gets nothing not the module-wide set`() {
        val registry = registryWithAnalyzers(listOf(fakeAnalyzer("review-comments")))
        val module = AiRuleModule(registry)
        val bindings = mapOf("code-suggestions" to listOf("ci:"))

        val result = module.ruleScopedEnrichment("review-comments", moduleEnrichment, bindings)

        assertEquals(EnrichmentContext.EMPTY.availableIds, result.availableIds)
    }

    @Test
    fun `analyzer-declared interest and config binding union together for the same rule`() {
        val registry = registryWithAnalyzers(
            listOf(fakeAnalyzer("ticket-compliance", listOf("confluence"))),
        )
        val module = AiRuleModule(registry)
        val bindings = mapOf("ticket-compliance" to listOf("gitlab:"))

        val result = module.ruleScopedEnrichment("ticket-compliance", moduleEnrichment, bindings)

        assertEquals(setOf("gitlab:mr", "confluence:page"), result.availableIds)
    }

    @Test
    fun `binding for one rule does not widen a different rule's analyzer-declared interest`() {
        val registry = registryWithAnalyzers(
            listOf(fakeAnalyzer("ticket-compliance", listOf("confluence"))),
        )
        val module = AiRuleModule(registry)
        val bindings = mapOf("review-comments" to listOf("gitlab:", "ci:"))

        val result = module.ruleScopedEnrichment("ticket-compliance", moduleEnrichment, bindings)

        assertEquals(setOf("confluence:page"), result.availableIds)
    }
}
