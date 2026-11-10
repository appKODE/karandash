package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.conditions.BuiltinPredicates
import io.karandash.conditions.ConditionContext
import io.karandash.config.Config
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User

/**
 * Parses every policy `when` and rule `when` expression against the predicates the run will have, so a
 * typo'd or unknown predicate is reported at load instead of silently turning the policy or rule off at run time.
 * Run it after the module registry is populated: a predicate such as `has_tag` exists only once its plugin is loaded.
 */
object ConditionExpressionCheck {
    private val placeholderContext = ConditionContext(
        mr = MergeRequest(
            id = "0",
            iid = 0,
            title = "",
            description = null,
            sourceBranch = "",
            targetBranch = "",
            author = User("", ""),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        ),
    )

    /** One message per expression in [config] that does not parse. */
    fun errors(
        config: Config,
        registry: ModuleRegistry,
    ): List<String> {
        val expressions = config.policies.orEmpty().mapIndexed { index, policy ->
            "policy '${policy.name ?: "#${index + 1}"}'" to policy.condition
        } + config.rules.ruleMeta.mapNotNull { (ruleId, meta) ->
            meta.`when`?.let { "rule '$ruleId'" to it.expression }
        }
        if (expressions.isEmpty()) return emptyList()
        // an empty prior-findings list registers the rule_* predicates, which later passes provide
        val evaluator = BuiltinPredicates.evaluator(
            placeholderContext,
            BuiltinPredicates.createRegistry(registry, priorFindings = emptyList()),
            patternExpander = null,
            maxExpressionLength = config.limits.expressions.effectiveMaxLength,
            maxNestingDepth = config.limits.expressions.effectiveMaxNestingDepth,
            maxRegexLength = config.limits.regex.effectiveMaxPatternLength,
        )
        return expressions.mapNotNull { (owner, expression) ->
            try {
                evaluator.validate(expression)
                null
            } catch (e: IllegalStateException) {
                "Invalid 'when' of $owner: ${e.message}"
            } catch (e: IllegalArgumentException) {
                "Invalid 'when' of $owner: ${e.message}"
            }
        }
    }
}
