package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.models.finding.Finding
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [AiRuleModule.supportedEnrichment] aggregates every registered
 * [AiAnalyzerModule.supportedEnrichment] -- without this, [AiRuleModule] never has a
 * non-empty [io.karandash.api.module.RuleModule.supportedEnrichment], so
 * `ModuleInitializer.deliverEnrichmentToRuleModules` never has a reason to re-initialize
 * it with real enrichment data, regardless of what any individual analyzer declares.
 *
 * Uses local fake analyzers rather than [buildTestRegistry] (whose stub analyzers don't
 * declare `supportedEnrichment`) to isolate the aggregation logic itself; the real
 * analyzers' own declarations (`ticket-compliance` -> jira/github/gitlab, `design-doc-drift`
 * -> jira/confluence, `description-quality` -> jira) are verified directly by reading
 * their source, not re-asserted here.
 */
class AiRuleModuleSupportedEnrichmentTest {
    private fun fakeAnalyzer(
        analyzerIdValue: String,
        interest: List<String>
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

    @Test
    fun `aggregates and deduplicates enrichment interest across all registered analyzers`() {
        val registry = registryWithAnalyzers(
            listOf(
                fakeAnalyzer("ticket-compliance", listOf("jira", "github", "gitlab")),
                fakeAnalyzer("design-doc-drift", listOf("jira", "confluence")),
                fakeAnalyzer("description-quality", listOf("jira")),
                fakeAnalyzer("review-comments", emptyList()),
            ),
        )
        val module = AiRuleModule(registry)

        assertEquals(
            setOf("jira", "github", "gitlab", "confluence"),
            module.supportedEnrichment.toSet(),
            "expected the union of every analyzer's supportedEnrichment, deduplicated",
        )
    }

    @Test
    fun `no analyzers means no supported enrichment`() {
        val registry = registryWithAnalyzers(emptyList())
        val module = AiRuleModule(registry)

        assertEquals(emptyList(), module.supportedEnrichment)
    }
}
