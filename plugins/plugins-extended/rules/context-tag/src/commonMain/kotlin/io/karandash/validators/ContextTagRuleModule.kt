package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.ConditionPredicate
import io.karandash.conditions.PredicateRegistry
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class ContextTagRuleModule : AbstractTypedSingleRuleModule<ContextTagConfig>(ContextTagConfig.serializer()) {
    companion object {
        const val RULE_ID = "contextTag"
    }

    override val defaultEnabled: Boolean = false
    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Ensures exactly one allowed context tag (e.g., [ios], [android], [core]) is present in MR and commit titles."

    override val name: String = "Context Tag Rule"
    override val description: String = "Validates that titles contain exactly one allowed context tag."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: ContextTagConfig,
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: ContextTagConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = ContextTagValidator(config, patterns).validate(mr).map { it.withFixedText(ContextTagText.entries) }

    override fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {
        registry.register(HasTagPredicate)
    }
}

/**
 * Checks whether the MR title contains a context tag of the form `[name]`, `(name)` or `{name}`.
 *
 * Usage: `has_tag(WIP)` -- checks for `[WIP]`, `(WIP)` or `{WIP}` in the title.
 * The tag name argument is matched case-insensitively.
 */
private object HasTagPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_tag"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val tag = args.getOrNull(0) ?: return false
        // Literal match; for non-ASCII tags `ignoreCase` folds Unicode case like the JVM regex it replaced.
        // The predicate has no access to the rule config, so every delimiter the rule supports counts.
        return listOf("[$tag]", "($tag)", "{$tag}").any { context.mr.title.contains(it, ignoreCase = true) }
    }
}
