package io.karandash.plugins.ai.core.pipeline

import io.karandash.conditions.CommonPredicates
import io.karandash.conditions.ConditionContext
import io.karandash.conditions.ExpressionEvaluator
import io.karandash.conditions.FieldMatchPredicates
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.Logger

/**
 * `ai.correlation.patterns[].when`: evaluates a pattern's condition with the shared conditions
 * engine (`is_draft`, `total_changes > 500`, `label_exists(...)`, `branch *= "hotfix-*"`, ...) against
 * the MR. A malformed expression is logged and treated as not matching.
 */
object CorrelationCondition {
    private val FIELDS: List<Pair<String, (ConditionContext) -> String>> = listOf(
        "branch" to { it.mr.sourceBranch },
        "title" to { it.mr.title },
        "author" to { it.mr.author.username },
        "description" to { it.mr.description.orEmpty() },
        "target_branch" to { it.mr.targetBranch },
    )

    /** Keeps the patterns without a `when` and those whose `when` holds for [mr]. */
    fun filter(
        patterns: List<CorrelationPatternConfig>,
        mr: MergeRequest?,
        findings: List<Finding>,
        logger: Logger,
    ): List<CorrelationPatternConfig> {
        if (patterns.none { it.whenCondition != null }) return patterns
        val evaluator = mr?.let { evaluator(it, findings) }
        return patterns.filter { pattern ->
            val expression = pattern.whenCondition ?: return@filter true
            evaluator != null && matches(evaluator, pattern.name, expression, logger)
        }
    }

    private fun matches(
        evaluator: ExpressionEvaluator<ConditionContext>,
        name: String,
        expression: String,
        logger: Logger,
    ): Boolean =
        try {
            evaluator.evaluate(expression)
        } catch (e: IllegalArgumentException) {
            logger.warn("Correlation pattern '$name': invalid when '$expression': ${e.message}")
            false
        } catch (e: IllegalStateException) {
            logger.warn("Correlation pattern '$name': invalid when '$expression': ${e.message}")
            false
        }

    /** The shared conditions engine over [mr]: common predicates plus the MR field operators (`branch *= "x"`). */
    internal fun evaluator(
        mr: MergeRequest,
        findings: List<Finding> = emptyList(),
    ): ExpressionEvaluator<ConditionContext> {
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        FIELDS.forEach { (field, extractor) -> FieldMatchPredicates.forField(field, extractor).forEach(registry::register) }
        val evaluator = ExpressionEvaluator(ConditionContext(mr, priorFindings = findings))
        evaluator.registerAll(registry)
        FIELDS.forEach { (field, _) -> evaluator.registerOperatorField(field, FieldMatchPredicates.operatorNames(field)) }
        return evaluator
    }
}
