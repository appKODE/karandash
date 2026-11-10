package io.karandash.plugins.ai

import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.config.AiCostBudgetResolver

/**
 * the per-run ledger of auxiliary AI calls (hooks, constitutional review, enricher, digest), keyed by the
 * analyzer that triggered each call. Auxiliary spend never draws on the shared run budget, so it cannot starve
 * the analyzers; `cost-budget.max-tokens-per-run` is the only cap on it.
 */
internal object AiAuxiliaryLedger {
    /** The enabled analyzers behind [findings]; with none, the analyzers that already ran (sorted, distinct). */
    fun triggeredBy(
        findings: List<Finding>,
        enabledRules: Set<String>,
        ran: Set<String>,
    ): List<String> =
        findings
            .map { it.rule }
            .filter { it in enabledRules }
            .ifEmpty { ran.filter { it in enabledRules } }
            .distinct()
            .sorted()

    /** Adds [tokens] to [ledger], split evenly across [analyzers]; with none (a post-fetch hook runs first) under [stage]. */
    fun charge(
        ledger: Map<String, Int>,
        stage: String,
        tokens: Int,
        analyzers: List<String>,
    ): Map<String, Int> {
        val ids = analyzers.ifEmpty { listOf(stage) }
        val share = tokens / ids.size
        val rest = tokens % ids.size
        return ids.foldIndexed(ledger) { i, acc, id -> acc + (id to (acc[id] ?: 0) + share + if (i < rest) 1 else 0) }
    }

    /**
     * Why auxiliary calls stop: run plus auxiliary spend reached `cost-budget.max-tokens-per-run` (branch-resolved).
     * Null below the cap or without a `cost-budget` block.
     */
    fun capReason(
        init: AiRuleInitState,
        state: AiRuleState,
        sourceBranch: String,
    ): String? {
        val costBudget = init.pluginCfg.costBudget ?: return null
        val cap = AiCostBudgetResolver.resolveTokenBudget(sourceBranch, costBudget)
        val spent = (init.tokenBudget?.totalConsumed() ?: 0) + state.auxiliaryTokens.values.sum()
        return if (spent >= cap) "cost-budget.max-tokens-per-run cap of $cap tokens reached ($spent used)" else null
    }
}
