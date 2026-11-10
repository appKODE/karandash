package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.finding.SkipCategory
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** A rule whose budget is already spent is skipped before its enrichers fetch anything. */
class AiRuleModuleBudgetSkipTest {
    @Test
    fun `an exhausted rule budget skips the rule before any enricher runs`() =
        runTest {
            val enricher = CountingEnricher()
            val module = initialized(enricher, ruleTokens = 0)

            val findings = module.validate(RULE, mr(), JsonObject(emptyMap()))

            assertEquals(emptyList(), findings)
            assertEquals(0, enricher.budgets.size, "no enrichment I/O for a rule that cannot run")
            assertEquals(SkipCategory.AI_BUDGET_EXHAUSTED, module.skippedRuleReasons()[RULE]?.category)
            module.shutdown()
        }

    @Test
    fun `a rule budget with room still runs its enrichers`() =
        runTest {
            val enricher = CountingEnricher()
            val module = initialized(enricher, ruleTokens = 100_000)

            module.validate(RULE, mr(), JsonObject(emptyMap()))

            assertEquals(1, enricher.budgets.size)
            module.shutdown()
        }

    private suspend fun initialized(
        enricher: AiContextEnricherModule,
        ruleTokens: Int,
    ): AiRuleModule {
        val allocator = FixedAllocator(ruleTokens)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(enricher)

            override fun aiTokenAllocator(): AiTokenAllocatorModule = allocator
        }
        return AiRuleModule(registry).also { it.initialize(context()) }
    }

    private fun context() =
        TestPluginContext(
            mapToJsonElement(mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model")),
            workingDirectory = "/tmp/test",
        )

    private fun mr() =
        createTestMergeRequest(
            changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, diffContent = "@@ -1 +1 @@\n-val a = 1\n+val a = 2")),
        )

    private class FixedAllocator(
        private val tokens: Int,
    ) : AiTokenAllocatorModule {
        override val id: String = "fixed-allocator"
        override val name: String = id
        override val version: String = "1"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun allocate(
            rules: Set<String>,
            totalBudget: Int,
            priorities: Map<String, Int>,
        ): Map<String, Int> = rules.associateWith { tokens }
    }

    private class CountingEnricher : AiContextEnricherModule {
        val budgets = mutableListOf<BlockBudget>()
        override val id: String = "counting-context"
        override val name: String = id
        override val version: String = "1"

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
        ): AiContextBlock? {
            budgets += budget
            return null
        }
    }

    private companion object {
        const val RULE = "review-comments"
    }
}
