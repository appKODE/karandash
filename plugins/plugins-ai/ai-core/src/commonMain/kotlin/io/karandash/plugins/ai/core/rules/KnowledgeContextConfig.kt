@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.plugins.ai.core.rules

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Budget configuration for a single enrichment ID (or pattern) in the AI prompt.
 *
 * `max-chars: 0` skips the block entirely for that ID/pattern.
 */
@Serializable
data class BudgetConfig(
    @SerialName("max-chars") @JsonNames("maxChars") val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    companion object {
        const val DEFAULT_MAX_CHARS: Int = 2_000
    }
}

/**
 * Controls how much of each enrichment ID's formatted block reaches the AI prompt.
 *
 * Keys in [budgets] are enrichment-ID patterns (exact or glob with `*`). Resolution
 * order: `rule-context.<rule>.knowledge-context.budgets["exact"]` → `["prefix:*"]` →
 * `ai.knowledge-context.budgets[…]` → [defaultBudget].
 *
 * `max-chars: 0` in any budget entry skips that ID's block for the rule entirely.
 */
@Serializable
data class KnowledgeContextConfig(
    @SerialName("default-budget") @JsonNames("defaultBudget") val defaultBudget: BudgetConfig = BudgetConfig(),
    val budgets: Map<String, BudgetConfig> = emptyMap(),
) {
    /**
     * Resolves the budget for [enrichmentId] using most-specific-pattern-wins:
     * exact match → longest matching glob prefix → [defaultBudget].
     */
    fun budgetFor(enrichmentId: String): BudgetConfig {
        budgets[enrichmentId]?.let { return it }
        budgets.entries
            .filter { (pattern, _) -> pattern.endsWith("*") && enrichmentId.startsWith(pattern.dropLast(1)) }
            .maxByOrNull { (pattern, _) -> pattern.length }
            ?.let { return it.value }
        return defaultBudget
    }
}
