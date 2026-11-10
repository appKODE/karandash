package io.karandash.conditions

import io.karandash.config.PolicyConfig
import io.karandash.models.ActivePolicy

/**
 * Evaluates a list of [PolicyConfig] entries against the current MR context,
 * producing [ActivePolicy] instances that describe which policies matched.
 */
fun interface PolicyEngine {
    /**
     * Evaluates [policies] against [context] using [predicates] for condition resolution.
     *
     * @param policies The policy configurations from the top-level `policies:` block.
     * @param context The MR condition context to evaluate against.
     * @param predicates Registry of available condition predicates.
     * @return List of [ActivePolicy] results, one per input policy.
     */
    suspend fun evaluate(
        policies: List<PolicyConfig>,
        context: ConditionContext,
        predicates: PredicateRegistry<ConditionContext>,
    ): List<ActivePolicy>
}
