package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No-cost sweep of the per-MR budget plan: MRs of 1 to 300 files under the default and a production-like config at
 * three context sizes, with the eval's rule set and a real-sized prompt. Asserts the plan never passes its ceiling
 * and funds higher priorities first; writes the whole table to `build/sizing-sweep.md` for reading.
 */
class ChunkBudgetSweepTest {
    private val registry = sweepRegistry()

    @Test
    fun `every MR size stays under the ceiling and funds higher priorities first`() {
        val rows = CONFIGS.flatMap { (name, cfg) ->
            CONTEXTS.flatMap { context -> FILES.map { files -> row(name, cfg.copy(contextBudget = context), files) } }
        }
        TestFiles.findRepoRoot()?.let { root ->
            TestFiles.writeText(Path(root, "plugins/plugins-ai/rules/ai/build/sizing-sweep.md"), report(rows))
        }
        rows.forEach { row ->
            row.ceiling?.let { assertTrue(row.runBudget <= it, "${row.key}: run ${row.runBudget} over ceiling $it") }
            assertTrue(row.priorityOrderKept, "${row.key}: a lower priority was funded while a higher one is short")
        }
    }

    private fun row(
        config: String,
        cfg: AiPluginConfig,
        files: Int,
    ): Row {
        val mr = createTestMergeRequest(changes = (1..files).map(::change))
        val shares = shares(cfg.maxTokenBudget)
        val run = TokenBudget(maxTokens = cfg.maxTokenBudget)
        val ruleConfig = AiRuleConfig()
        val plan = ChunkBudget(registry).ledger(ChunkBudget.Input(shares, run, mr, cfg, ruleConfig, MODEL), NoOpLogger)
        val diffChars = mr.changes.sumOf { it.diffContent.orEmpty().length }
        val cover = plan.rules.associate { it.rule to coverage(it, shares.getValue(it.rule).maxTokens, mr, cfg, diffChars) }
        val short = plan.rules.filter { it.grant < it.need - it.share }.map { it.priority }
        val funded = plan.rules.filter { it.need > it.share && it.grant > 0 }.map { it.priority }
        return Row(
            key = "$config ctx=${cfg.contextBudget} files=$files",
            diffTokens = plan.rules.first().diffTokens,
            runBudget = plan.runBudget,
            ceiling = plan.ceiling,
            rules = plan.rules.associate { it.rule to "${it.calls}x${it.call}" },
            coverage = cover,
            notes = plan.notes.size,
            priorityOrderKept = short.none { s -> funded.any { it < s } },
        )
    }

    /** Percent of the MR's diff characters the rule's budget pays for. */
    private fun coverage(
        sizing: RuleSizing,
        budget: Int,
        mr: MergeRequest,
        cfg: AiPluginConfig,
        diffChars: Int,
    ): Int {
        if (sizing.skipReason != null || diffChars == 0) return 0
        val sent = if (sizing.chunked) {
            // Run time holds each call's prompt, reserve, chunk and (chunk-context) context half until the budget runs out.
            var left = budget
            ChunkBudget
                .split(mr, cfg, AiRuleConfig(maxDiffCharsTotal = sizing.chunkChars))
                .chunks
                .takeWhile { chunk ->
                    val diff = chunk.changes.sumOf { TokenUnit.tokensOf(it.diffContent.orEmpty()) }
                    val context = if (cfg.largeMr.chunkContext) (sizing.call - sizing.t0 - sizing.reserve) / 2 else 0
                    left -= minOf(sizing.call, sizing.t0 + sizing.reserve + diff + context)
                    left >= 0
                }.sumOf { it.totalChars }
        } else {
            ChunkBudget.charsOf(minOf(budget, sizing.call) - sizing.t0 - sizing.reserve, mr).coerceAtLeast(0)
        }
        return (minOf(sent, diffChars).toLong() * PERCENT / diffChars).toInt()
    }

    private fun shares(total: Int): Map<String, TokenBudget> {
        val others = RULES - AiRuleIds.REVIEW_COMMENTS
        return mapOf(AiRuleIds.REVIEW_COMMENTS to TokenBudget(maxTokens = total / 2)) +
            others.associateWith { TokenBudget(maxTokens = total / 2 / others.size) }
    }

    private fun report(rows: List<Row>): String =
        buildString {
            appendLine("| config | diff tok | run | ceiling | notes | " + RULES.joinToString(" | ") + " |")
            appendLine("|" + "---|".repeat(RULES.size + 5))
            rows.forEach { r ->
                val cells = RULES.map { "${r.rules[it]} ${r.coverage[it]}%" }
                appendLine(
                    "| ${r.key} | ${r.diffTokens} | ${r.runBudget} | ${r.ceiling ?: "-"} | ${r.notes} | ${cells.joinToString(" | ")} |"
                )
            }
        }

    /** File [i]'s diff: 80-character lines, 10 to 110 of them, so files vary like a real MR. */
    private fun change(i: Int): FileChange {
        val lines = LINES_MIN + (i * LINES_STEP) % LINES_SPREAD
        val diff = (1..lines).joinToString("\n") { "+" + "v".repeat(LINE_CHARS) }
        return FileChange(path = "src/F$i.kt", changeType = ChangeType.MODIFIED, additions = lines, deletions = 0, diffContent = diff)
    }

    private class Row(
        val key: String,
        val diffTokens: Int,
        val runBudget: Int,
        val ceiling: Int?,
        val rules: Map<String, String>,
        val coverage: Map<String, Int>,
        val notes: Int,
        val priorityOrderKept: Boolean,
    )

    private class SweepAnalyzer(
        override val analyzerId: String,
        override val mrLevel: Boolean,
    ) : AiAnalyzerModule {
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultPrompt: String = "p".repeat(PROMPT_CHARS)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }

    private fun sweepRegistry(): ModuleRegistry {
        val analyzers = RULES.map { SweepAnalyzer(it, mrLevel = it in WHOLE_MR) }
        return object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }
        }
    }

    private companion object {
        const val MODEL = "moonshotai/kimi-k2.5"
        const val PERCENT = 100

        /** About 3.5k tokens: the eval's review-comments T0 was 3.9k with MR metadata. */
        const val PROMPT_CHARS = 14_000
        const val LINE_CHARS = 79
        const val LINES_MIN = 10
        const val LINES_STEP = 37
        const val LINES_SPREAD = 100
        val WHOLE_MR = setOf("mr-summary", "review-thread-analysis", "risk-scoring")
        val RULES = listOf(AiRuleIds.REVIEW_COMMENTS, "code-suggestions") + WHOLE_MR
        val FILES = listOf(1, 5, 10, 26, 50, 100, 200, 300)
        val CONTEXTS = listOf(16_000, 32_000, 64_000)
        val CONFIGS = listOf(
            "default" to AiPluginConfig(),
            "base" to AiPluginConfig(maxTokenBudget = 15_000, costBudget = CostBudgetConfig(maxTokensPerRun = 80_000)),
        )
    }
}
