package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.enrichment.localgit.LocalGitEnrichmentContextEnricher
import io.karandash.enrichment.localgit.LocalGitEnrichmentProvider
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.mergedEnricherConfigJson
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class AiPromptAssemblerLocalGitTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    @Test
    fun `local git provider block reaches prompt`() =
        runTest {
            val changes = listOf(FileChange(path = "src/Auth.kt", changeType = ChangeType.MODIFIED, additions = 1))
            val mr = createTestMergeRequest(changes = changes)
            val providerOutput = LocalGitEnrichmentProvider.fileFrequencyJson(mapOf("src/Auth.kt" to 12), "3 months")
            val enrichment = EnrichmentContext(data = mapOf(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY to providerOutput))
            val base = buildTestRegistry()
            val registry = object : ModuleRegistry by base {
                override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(LocalGitEnrichmentContextEnricher())
            }

            val prompt = AiPromptAssembler(registry, noopLogger).buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = mr,
                changes = changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = mergedEnricherConfigJson(JsonObject(emptyMap()), enrichment),
                needsScopeDetection = { false },
            )

            assertNotNull(prompt)
            assertContains(prompt, LocalGitEnrichmentContextEnricher.BLOCK_HEADING)
            assertContains(prompt, "Frequently changed files (last 3 months)")
            assertContains(prompt, "src/Auth.kt: 12 commits")
        }
}
