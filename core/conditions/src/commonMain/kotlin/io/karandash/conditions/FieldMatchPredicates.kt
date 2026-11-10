package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.helpers.RegexHelper
import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.TextGlobCompiler

/**
 * Factory for generating field-matching predicates (`*_matches`, `*_glob_matches`,
 * `*_regex_matches`, `*_exact_matches`) for a named MR field.
 *
 * Produces 4 predicates per field and optionally integrates with [PatternResolver]
 * for the `{field}_matches` predicate.
 */
object FieldMatchPredicates {
    private val globCompiler = TextGlobCompiler()

    /**
     * Creates 3 operator-syntax predicates for [fieldName] using [fieldExtractor].
     *
     * Produces `{field}_glob_matches`, `{field}_regex_matches`, `{field}_exact_matches`.
     * The base `{field}_matches` predicate (which may need special args like
     * `branch_matches(pattern, target)`) should be registered separately.
     *
     * @param fieldName Base name (e.g., "branch", "title", "author").
     * @param fieldExtractor Extracts the field value from [ConditionContext].
     * @param regexCacheFn Optional cached regex function for the regex predicate.
     * @return List of 3 operator predicates for the field.
     */
    fun forField(
        fieldName: String,
        fieldExtractor: (ConditionContext) -> String,
        regexCacheFn: ((String) -> Regex)? = null,
    ): List<ConditionPredicate<ConditionContext>> =
        listOf(
            FieldGlobMatchesPredicate(fieldName, fieldExtractor),
            FieldRegexOnlyMatchesPredicate(fieldName, fieldExtractor, regexCacheFn),
            FieldExactMatchesPredicate(fieldName, fieldExtractor),
        )

    /**
     * Returns the [ExpressionEvaluator.OperatorPredicateNames] for a field,
     * mapping `*=`, `~=`, `=` operators to the generated predicate names.
     */
    fun operatorNames(fieldName: String,): ExpressionEvaluator.OperatorPredicateNames =
        ExpressionEvaluator.OperatorPredicateNames(
            globName = "${fieldName}_glob_matches",
            regexName = "${fieldName}_regex_matches",
            exactName = "${fieldName}_exact_matches",
        )

    /** `{field}_glob_matches` -- glob match via [TextGlobCompiler]. */
    private class FieldGlobMatchesPredicate(
        fieldName: String,
        private val fieldExtractor: (ConditionContext) -> String,
    ) : ConditionPredicate<ConditionContext> {
        override val name: String = "${fieldName}_glob_matches"
        override val arity: Int = 1

        override fun evaluate(
            args: List<String>,
            context: ConditionContext,
        ): Boolean {
            val glob = args.getOrNull(0) ?: return false
            return when (val result = globCompiler.compile(glob)) {
                is PatternCompilationResult.Success -> result.regex.containsMatchIn(fieldExtractor(context))
                is PatternCompilationResult.Error -> false
            }
        }
    }

    /** `{field}_regex_matches` -- regex match for `~=` operator syntax. */
    private class FieldRegexOnlyMatchesPredicate(
        fieldName: String,
        private val fieldExtractor: (ConditionContext) -> String,
        private val regexCacheFn: ((String) -> Regex)?,
    ) : ConditionPredicate<ConditionContext> {
        override val name: String = "${fieldName}_regex_matches"
        override val arity: Int = 1
        override val isRegexPredicate: Boolean = true

        override fun evaluate(
            args: List<String>,
            context: ConditionContext,
        ): Boolean {
            val pattern = args.getOrNull(0) ?: return false
            val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
            return regex.containsMatchIn(fieldExtractor(context))
        }
    }

    /** `{field}_exact_matches` -- exact string match for `=` operator syntax. */
    private class FieldExactMatchesPredicate(
        fieldName: String,
        private val fieldExtractor: (ConditionContext) -> String,
    ) : ConditionPredicate<ConditionContext> {
        override val name: String = "${fieldName}_exact_matches"
        override val arity: Int = 1

        override fun evaluate(
            args: List<String>,
            context: ConditionContext,
        ): Boolean {
            val value = args.getOrNull(0) ?: return false
            return fieldExtractor(context) == value
        }
    }
}
