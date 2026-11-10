package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.ChangeType

/**
 * File metric predicates for condition expressions.
 *
 * Includes comparison predicates for file counts by change type
 * and function predicates for file type and addition checks.
 */
object FileMetricPredicates {
    /**
     * Registers all file metric predicates into [registry].
     */
    fun registerAll(registry: PredicateRegistry<ConditionContext>) {
        registry.register(FileCountPredicate)
        registry.register(FileCountAddedPredicate)
        registry.register(FileCountDeletedPredicate)
        registry.register(FileCountModifiedPredicate)
        registry.register(HasFileTypePredicate)
        registry.register(FileAddedPredicate)
    }
}

private object FileCountPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_count"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.changes.size, op, value)
    }
}

private object FileCountAddedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_count_added"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val count = context.mr.changes.count { it.changeType == ChangeType.ADDED }
        return ExpressionEvaluator.compareInt(count, op, value)
    }
}

private object FileCountDeletedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_count_deleted"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val count = context.mr.changes.count { it.changeType == ChangeType.DELETED }
        return ExpressionEvaluator.compareInt(count, op, value)
    }
}

private object FileCountModifiedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_count_modified"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val count = context.mr.changes.count { it.changeType == ChangeType.MODIFIED }
        return ExpressionEvaluator.compareInt(count, op, value)
    }
}

private object HasFileTypePredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_file_type"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val ext = args.getOrNull(0) ?: return false
        return context.mr.changes.any { it.path.endsWith(ext) }
    }
}

private object FileAddedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_added"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        return context.mr.changes.any {
            it.changeType == ChangeType.ADDED && it.path.contains(pattern)
        }
    }
}
