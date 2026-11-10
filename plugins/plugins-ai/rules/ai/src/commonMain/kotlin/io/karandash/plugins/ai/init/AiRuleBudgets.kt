package io.karandash.plugins.ai.init

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.serialization.json.JsonObject

/** The run token budget and each enabled analyzer's share of it. */
internal class RuleBudgets(
    val run: TokenBudget,
    val perRule: Map<String, TokenBudget>,
)

/**
 * Token budgets of the AI rules.
 *
 * Without `ai.<analyzer>.max-tokens` values `ai.max-token-budget` is split between the enabled analyzers:
 * review-comments, the primary analyzer, takes half when others share the split, the rest is split between them. With them, each analyzer gets its explicit value; the others share what is left of the budget through
 * the registered `aiTokenAllocator`, or get their estimate without one. A set `ai.max-token-budget` (config or
 * `KARANDASH_AI_MAX_TOKEN_BUDGET`) stays the hard run ceiling; unset, the run budget is the larger of the default
 * and the sum. A sum over the ceiling or over `cost-budget.max-tokens-per-run` is warned about at load, naming the
 * analyzers that may be cut.
 *
 * [AiRuleIds.SEPARATE_PRODUCERS] without an explicit value and with an estimate get it as their own budget, outside the
 * split, so enabling one leaves the other analyzers' shares unchanged. The run budget grows by those estimates only
 * when `ai.max-token-budget` is unset; a set one stays the run ceiling and the producers may be cut by it.
 */
internal class AiRuleBudgets(
    private val registry: ModuleRegistry,
) {
    suspend fun build(
        enabledRules: Set<String>,
        pluginCfg: AiPluginConfig,
        context: PluginContext,
        toRuleId: (String) -> String,
    ): RuleBudgets {
        val explicit = pluginCfg.analyzerMaxTokens.entries
            .associate { (key, tokens) -> toRuleId(key) to tokens }
            .filterKeys { it in enabledRules }
        val own = ((enabledRules intersect AiRuleIds.SEPARATE_PRODUCERS) - explicit.keys)
            .associateWith { estimatedTokens(it) }
            .filterValues { it > 0 }
        val shared = enabledRules - own.keys
        val ceilingSet = (context.pluginConfig as? JsonObject)?.containsKey(MAX_TOKEN_BUDGET_KEY) == true ||
            context.env(AiEnvVars.KARANDASH_AI_MAX_TOKEN_BUDGET) != null
        warnIfBudgetTooSmall(shared, pluginCfg, context)
        if (explicit.isEmpty()) {
            if (own.isNotEmpty()) {
                val split = mapOf(SHARED_SPLIT to pluginCfg.maxTokenBudget)
                // Under scale-budget the plan grows the run past max-token-budget, so the sum is not a cut.
                if (ceilingSet && !pluginCfg.largeMr.scaleBudget) {
                    warnIfOverCap(split + own, pluginCfg.maxTokenBudget, "max-token-budget", context)
                }
                pluginCfg.costBudget?.let { warnIfOverCap(split + own, it.maxTokensPerRun, "cost-budget.max-tokens-per-run", context) }
            }
            val run = TokenBudget(pluginCfg.maxTokenBudget + if (ceilingSet) 0 else own.values.sum())
            return RuleBudgets(run, splitBudget(shared, pluginCfg, context) + own.mapValues { (_, t) -> TokenBudget(t) })
        }
        val others = enabledRules - explicit.keys
        val left = (pluginCfg.maxTokenBudget - explicit.values.sum()).coerceAtLeast(0)
        val allocator = registry.aiTokenAllocator()
        val shares = if (allocator != null && others.isNotEmpty()) allocator.allocate(others, left, emptyMap()) else emptyMap()
        val evenShare = if (others.isEmpty()) 0 else left / others.size
        val perRule = enabledRules.associateWith { rule ->
            explicit[rule] ?: shares[rule] ?: estimatedTokens(rule).takeIf { it > 0 } ?: evenShare
        }
        if (ceilingSet && !pluginCfg.largeMr.scaleBudget) warnIfOverCap(perRule, pluginCfg.maxTokenBudget, "max-token-budget", context)
        pluginCfg.costBudget?.let { warnIfOverCap(perRule, it.maxTokensPerRun, "cost-budget.max-tokens-per-run", context) }
        val run = TokenBudget(if (ceilingSet) pluginCfg.maxTokenBudget else maxOf(pluginCfg.maxTokenBudget, perRule.values.sum()))
        return RuleBudgets(run, perRule.mapValues { (_, tokens) -> TokenBudget(tokens) })
    }

    private suspend fun splitBudget(
        enabledRules: Set<String>,
        pluginCfg: AiPluginConfig,
        context: PluginContext,
    ): Map<String, TokenBudget> {
        val maxTokens = pluginCfg.maxTokenBudget
        val allocator = registry.aiTokenAllocator()
        if (allocator != null) {
            val allocation = allocator.allocate(enabledRules, maxTokens, emptyMap())
            return allocation.mapValues { (_, tokens) -> TokenBudget(tokens) }
        }
        if (maxTokens < enabledRules.size) return emptyMap()
        val adaptive = pluginCfg.costBudget?.adaptiveAllocation == true
        // The primary analyzer is never starved by the secondary ones: it takes a fixed share first.
        val primary = (setOf(AiRuleIds.REVIEW_COMMENTS) intersect enabledRules).takeIf { enabledRules.size > 1 }.orEmpty()
        val primaryTokens = primary.associateWith { maxTokens * PRIMARY_SHARE_PERCENT / PERCENT }
        val others = enabledRules - primary
        val estimatedCosts = others.associateWith { estimatedTokens(it) }
        val store = if (adaptive) StatisticsStore(pluginCfg.statistics) else null
        val allocation = primaryTokens + TokenBudget.perRule(others, maxTokens - primaryTokens.values.sum(), estimatedCosts, store)
        if (adaptive || primary.isNotEmpty()) {
            val breakdown = allocation.entries.joinToString { (rule, tokens) -> "$rule=$tokens" }
            context.log("AI plugin: ${if (adaptive) "Adaptive budget" else "Budget"} allocation ($maxTokens tokens): $breakdown")
        }
        return allocation.mapValues { (_, tokens) -> TokenBudget(tokens) }
    }

    private fun warnIfOverCap(
        perRule: Map<String, Int>,
        cap: Int,
        capName: String,
        context: PluginContext,
    ) {
        val total = perRule.values.sum()
        if (total <= cap) return
        val cumulative = perRule.values.runningReduce(Int::plus)
        val cut = perRule.keys
            .zip(cumulative)
            .filter { (_, sum) -> sum > cap }
            .map { (rule, _) -> rule }
        context.warn(
            "AI plugin: per-analyzer max-tokens add up to $total tokens, over $capName $cap; the cap is kept, " +
                "analyzers that may be cut: ${cut.joinToString(", ")}",
        )
    }

    private fun warnIfBudgetTooSmall(
        enabledRules: Set<String>,
        pluginCfg: AiPluginConfig,
        context: PluginContext,
    ) {
        if (pluginCfg.analyzerMaxTokens.isNotEmpty()) return
        val totalEstimated = enabledRules.sumOf { estimatedTokens(it) }
        if (totalEstimated > pluginCfg.maxTokenBudget) {
            context.warn(
                "AI plugin: Token budget (${pluginCfg.maxTokenBudget} tokens) may be too small for all enabled rules " +
                    "(estimated total: ~$totalEstimated tokens). Consider increasing maxTokenBudget.",
            )
        }
    }

    private fun estimatedTokens(ruleId: String): Int = registry.findAiAnalyzer(ruleId)?.estimatedTokens ?: 0

    private companion object {
        const val MAX_TOKEN_BUDGET_KEY = "maxTokenBudget"
        const val SHARED_SPLIT = "max-token-budget split"
        const val PRIMARY_SHARE_PERCENT = 50
        const val PERCENT = 100
    }
}
