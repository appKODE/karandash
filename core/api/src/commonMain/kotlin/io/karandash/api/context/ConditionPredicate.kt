package io.karandash.api.context

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.MergeRequestField

/**
 * A pluggable predicate for conditional expression evaluation.
 *
 * Predicates are registered with an [ExpressionEvaluator] and invoked
 * when a matching function call is encountered in an expression.
 *
 * @param C The context type that predicates evaluate against.
 */
public interface ConditionPredicate<in C> {
    /** The predicate name as used in condition expressions (e.g., "file_path_matches"). */
    public val name: String

    /** Number of required arguments. Use -1 for variable arity. */
    public val arity: Int

    /** Whether this predicate's arguments should be validated as regex patterns. */
    public val isRegexPredicate: Boolean get() = false

    /** Whether this predicate uses comparison operators (e.g., `change_count > 5`). */
    public val isComparisonPredicate: Boolean get() = false

    /** Whether this predicate is bare (no parentheses required, e.g., `is_draft`). */
    public val isBarePredicate: Boolean get() = false

    /**
     * The merge-request field this predicate reads, or `null` when it reads none a platform may leave unfilled.
     * On a platform listing it in [PlatformServiceModule.unavailableMergeRequestFields] the predicate sees an empty
     * value, so the engine warns about every `when` that uses it. Default: `null`.
     */
    @ExperimentalKarandashApi
    public val requiredField: MergeRequestField? get() = null

    /**
     * Evaluate this predicate against the given [context].
     *
     * @param args The arguments passed in the condition expression.
     * @param context The evaluation context.
     * @return `true` if the predicate matches.
     */
    public fun evaluate(
        args: List<String>,
        context: C,
    ): Boolean
}
