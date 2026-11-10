package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.context.FullFileContextEnricher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An enricher that declares enrichment ids is called only when the rule's enricher config carries one of
 * them (an enabled provider delivered data the rule is scoped to); an enricher without ids is always called.
 */
class AiPromptAssemblerEnricherGateTest {
    private val changes = listOf(FileChange(path = "src/A.kt", changeType = ChangeType.MODIFIED, additions = 1, diffContent = "+a"))
    private val mr = createTestMergeRequest(changes = changes)

    private fun enricher(
        enricherId: String,
        ids: List<String>,
        marker: String,
    ): AiContextEnricherModule =
        object : AiContextEnricherModule {
            override val id: String = enricherId
            override val name: String = enricherId
            override val version: String = "1.0"

            override fun handledEnrichmentIds(): List<String> = ids

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun enrich(
                mr: MergeRequest,
                context: AiContextEnricherContext,
            ): Map<String, String> = emptyMap()

            override suspend fun provideBlock(
                mr: MergeRequest,
                changes: List<FileChange>,
                config: JsonElement,
                budget: BlockBudget,
            ): AiContextBlock = AiContextBlock("#### $enricherId", marker, priority = 100, source = enricherId)
        }

    private suspend fun assemble(
        enricherConfig: JsonElement,
        ruleId: String = AiRuleIds.REVIEW_COMMENTS,
    ): String {
        val base = buildTestRegistry()
        val registry = object : ModuleRegistry by base {
            override fun aiContextEnrichers(): List<AiContextEnricherModule> =
                listOf(
                    enricher("hotspots", listOf(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY), HOTSPOT),
                    enricher("flagged", emptyList(), FLAGGED),
                    enricher(FullFileContextEnricher.MODULE_ID, emptyList(), FULL_FILE),
                )

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? =
                base.findAiAnalyzer(analyzerId)?.let { analyzer ->
                    if (analyzerId != AiRuleIds.RISK_SCORING) return@let analyzer
                    object : AiAnalyzerModule by analyzer {
                        override val mrLevel: Boolean get() = true
                    }
                }
        }
        return AiPromptAssembler(registry, NoOpLogger)
            .buildExtraInstructionsWithContext(
                ruleId = ruleId,
                mr = mr,
                changes = changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = enricherConfig,
                needsScopeDetection = { false },
            ).orEmpty()
    }

    @Test
    fun `an enricher whose enrichment ids were not delivered is not called`() =
        runTest {
            val prompt = assemble(JsonObject(emptyMap()))

            assertFalse(HOTSPOT in prompt, "enricher ran without its enrichment data")
            assertTrue(FLAGGED in prompt, "an enricher without enrichment ids must still run")
        }

    @Test
    fun `an enricher whose enrichment id was delivered is called`() =
        runTest {
            val prompt = assemble(buildJsonObject { put(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY, JsonObject(emptyMap())) })

            assertTrue(HOTSPOT in prompt, "enricher with delivered data was skipped")
        }

    @Test
    fun `an MR-level analyzer gets no full-file context`() =
        runTest {
            assertTrue(FULL_FILE in assemble(JsonObject(emptyMap())), "a line-anchored rule lost its full-file block")

            val prompt = assemble(JsonObject(emptyMap()), AiRuleIds.RISK_SCORING)

            assertFalse(FULL_FILE in prompt, "risk-scoring got a full-file block")
            assertTrue(FLAGGED in prompt, "other enrichers must still run for an MR-level analyzer")
        }

    private companion object {
        const val FULL_FILE = "FULL-FILE-MARKER"
        const val HOTSPOT = "HOTSPOT-MARKER"
        const val FLAGGED = "FLAGGED-MARKER"
    }
}
