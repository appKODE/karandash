package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.api.context.ConditionPredicate
import io.karandash.api.platform.PredicateRegistry
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity

/**
 * Predicates that query findings from prior validation passes.
 *
 * Registers `rule_has_findings`, `rule_passed`, and `rule_failed` into a
 * [PredicateRegistry] for use in policy `when:` expressions and per-rule
 * `when:` conditions during pass-2 execution.
 *
 * These predicates are standalone and registered by `ValidationEngine`
 * at the pass-1/pass-2 boundary.
 */
object RuleResultPredicates {
    /**
     * Registers rule-result predicates that evaluate against [findingsByRule].
     *
     * @param registry The predicate registry to register into.
     * @param findingsByRule Map of rule ID to findings from prior passes.
     */
    fun register(
        registry: PredicateRegistry<ConditionContext>,
        findingsByRule: Map<String, List<Finding>>,
    ) {
        registry.register(RuleHasFindingsPredicate(findingsByRule))
        registry.register(RulePassedPredicate(findingsByRule))
        registry.register(RuleFailedPredicate(findingsByRule))
    }
}

private class RuleHasFindingsPredicate(
    private val findingsByRule: Map<String, List<Finding>>,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "rule_has_findings"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val ruleId = args.getOrNull(0) ?: return false
        return findingsByRule[ruleId]?.isNotEmpty() == true
    }
}

private class RulePassedPredicate(
    private val findingsByRule: Map<String, List<Finding>>,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "rule_passed"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val ruleId = args.getOrNull(0) ?: return false
        return findingsByRule[ruleId]?.none { it.severity == Severity.ERROR } != false
    }
}

private class RuleFailedPredicate(
    private val findingsByRule: Map<String, List<Finding>>,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "rule_failed"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val ruleId = args.getOrNull(0) ?: return false
        return findingsByRule[ruleId]?.any { it.severity == Severity.ERROR } == true
    }
}
