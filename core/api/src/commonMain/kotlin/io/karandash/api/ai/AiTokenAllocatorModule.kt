package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType

/**
 * Allocates a total token budget across multiple AI rules.
 *
 * When registered, the allocator is called once during [AiRuleModule][io.karandash.plugins.ai.AiRuleModule]
 * initialization to produce per-rule budgets. Only one allocator is active at a time
 * (the one with the lowest [defaultPriority] wins).
 *
 * The default behavior (no allocator registered) splits the budget equally.
 */
public interface AiTokenAllocatorModule : Module {
    override val type: ModuleType get() = ModuleType.AI_TOKEN_ALLOCATOR

    /**
     * Allocate [totalBudget] tokens across the given [rules].
     *
     * @param rules Set of enabled rule IDs that will consume tokens.
     * @param totalBudget Total token budget for the validation run.
     * @param priorities Per-rule priority hints (lower = higher priority). May be empty.
     * @return Map of rule ID to allocated token count. Rules not in the map get zero budget.
     *
     * Suspending (see [AiRedactorModule.redact]).
     */
    public suspend fun allocate(
        rules: Set<String>,
        totalBudget: Int,
        priorities: Map<String, Int>,
    ): Map<String, Int>
}
