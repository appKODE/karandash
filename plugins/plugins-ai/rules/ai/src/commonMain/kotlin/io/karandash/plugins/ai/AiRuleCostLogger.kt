package io.karandash.plugins.ai

import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.config.CostEstimateConfig
import io.karandash.plugins.ai.dispatch.RuleCoverage
import io.karandash.plugins.ai.dispatch.named
import io.karandash.utils.DurationFormatter

internal object AiRuleCostLogger {
    /** Builds the end-of-run cost summary: a headline plus one line per enabled rule; empty when no rule is enabled. */
    fun summaryLines(
        state: AiRuleInitState,
        snapshot: AiRuleState,
    ): List<String> {
        val rules = state.enabledRules
        if (rules.isEmpty() || state.pluginCfg.costBudget
                ?.costEstimate
                ?.enabled == false
        ) {
            return emptyList()
        }
        val budgetConsumed = state.tokenBudget?.totalConsumed() ?: 0
        val aux = snapshot.auxiliaryTokens
        val samples = snapshot.sampleTokens
        val consumed = totalTokens(budgetConsumed, snapshot)
        val costStr = AiCostEstimator.formatCost(snapshot.totalEstimatedCostUsd)
        val totalMs = state.pluginStartMark?.elapsedNow()?.inWholeMilliseconds
        val totalTimeStr = if (totalMs != null) ", total ${DurationFormatter.formatMs(totalMs)}" else ""
        val split = splitText(snapshot.ruleInputTokens.values.sum(), snapshot.ruleOutputTokens.values.sum())
        val sampleStr = samples.values
            .sum()
            .takeIf { it > 0 }
            ?.let { ", $it of them extra samples outside the run budget" }
            .orEmpty()
        val headline = "AI plugin cost estimate: $costStr ($consumed tokens total$split$sampleStr$totalTimeStr)"
        return listOf(headline) +
            rules.map { ruleId ->
                ruleCostLine(
                    ruleId = ruleId,
                    tokens = listOfNotNull(
                        snapshot.ruleTokenCounts[ruleId]?.let {
                            "$it tokens" + splitText(snapshot.ruleInputTokens[ruleId] ?: 0, snapshot.ruleOutputTokens[ruleId] ?: 0) +
                                samples[ruleId]?.let { n -> ", $n in extra samples" }.orEmpty()
                        },
                        aux[ruleId]?.let { "$it auxiliary tokens" },
                    ).joinToString(" + ").ifEmpty { null },
                    skipReason = (snapshot.ruleSkipReasons[ruleId] ?: snapshot.engineSkips[ruleId])?.reason,
                    timingMs = snapshot.ruleTimings[ruleId],
                    coverage = snapshot.ruleCoverage[ruleId],
                    fromCache = snapshot.cachedFindings != null,
                )
            } + (aux - rules).map { (stage, tokens) -> "  $stage (auxiliary): $tokens tokens" }
    }

    /**
     * Every token of the run: the run budget's spend, or the metered rule counts when nothing was charged to it, plus
     * the extra samples (which spend a budget of their own, never the run budget) and the auxiliary calls.
     */
    fun totalTokens(
        budgetConsumed: Int,
        snapshot: AiRuleState,
    ): Int {
        val rules = if (budgetConsumed > 0) budgetConsumed + snapshot.sampleTokens.values.sum() else snapshot.ruleTokenCounts.values.sum()
        return rules + snapshot.auxiliaryTokens.values.sum()
    }

    /** `ai.costBudget.costEstimate` alert text when [costUsd] exceeds the configured threshold, else null. */
    fun alertMessage(
        costUsd: Double,
        config: CostEstimateConfig?,
    ): String? {
        if (config == null || !config.sendAlert || config.alertThreshold <= 0.0 || costUsd <= config.alertThreshold) return null
        val cost = AiCostEstimator.formatCost(costUsd)
        val threshold = AiCostEstimator.formatCost(config.alertThreshold)
        return "AI plugin: Cost alert threshold exceeded: estimated cost $cost > $threshold threshold"
    }

    private fun splitText(
        input: Int,
        output: Int,
    ): String = if (input + output > 0) ", $input in / $output out" else ""

    internal fun ruleCostLine(
        ruleId: String,
        tokens: String?,
        skipReason: String?,
        timingMs: Long?,
        coverage: RuleCoverage?,
        fromCache: Boolean = false,
    ): String {
        // The coverage ledger names a budget skip even when no skip reason reached the run state.
        val reason = skipReason ?: coverage?.skipReason
        return when {
            reason != null -> {
                "  $ruleId: skipped ($reason)"
            }

            tokens != null -> {
                val timingStr = if (timingMs != null) ", ${DurationFormatter.formatMs(timingMs)}" else ""
                val skipped = coverage?.takeIf { it.chunksSkipped > 0 }
                val skippedStr = skipped?.let { ", skipped ${it.chunksSkipped} of ${it.chunks} chunks (not reviewed)" } ?: ""
                "  $ruleId: ok, $tokens$timingStr$skippedStr${coverage?.let(::leftOut).orEmpty()}"
            }

            // No timing means the rule never reached the provider; "ran" would claim a call that did not happen.
            timingMs == null -> {
                "  $ruleId: ${if (fromCache) "from cache" else "not run"}"
            }

            else -> {
                "  $ruleId: ok, no tokens reported (${DurationFormatter.formatMs(timingMs)})${coverage?.let(::leftOut).orEmpty()}"
            }
        }
    }
}

/** `, left out: a, b, context left out: c, excluded: d (deleted)`; empty when the rule left nothing out. */
private fun leftOut(coverage: RuleCoverage): String =
    (if (coverage.dropped.isEmpty()) "" else ", left out: ${named(coverage.dropped)}") +
        (if (coverage.contextDropped.isEmpty()) "" else ", context left out: ${named(coverage.contextDropped)}") +
        (if (coverage.excluded.isEmpty()) "" else ", excluded: ${named(coverage.excluded)}")
