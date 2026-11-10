package io.karandash.conditions

/**
 * The one boolean engine behind every condition language.
 *
 * A language with leaves of its own (the `extends` `if:` expressions, the KTS `if` conditions) renders its `&&`, `||`,
 * `!` and parentheses around [leaf] references and evaluates the result here, so it shares [ExpressionEvaluator]'s
 * precedence (`!` binds tightest, then `&&`, then `||`), short-circuiting and nesting limit with rule `when:`
 * conditions. Leaves are evaluated lazily, only when the engine reaches them.
 */
object AtomExpression {
    private const val PREDICATE = "atom"

    /**
     * The expression text standing for the leaf at [index] of the list given to [evaluate]: a bare predicate, so a
     * leaf adds no parentheses to the nesting depth.
     */
    fun leaf(index: Int): String = "$PREDICATE$index"

    /**
     * Evaluates [expression], built from [leaf] references, `&&`, `||`, `!` and parentheses, against [leaves].
     * The caller limits the length of its own source text; the rendered text is not limited again.
     *
     * @throws IllegalArgumentException if the expression nests deeper than the engine allows.
     */
    fun evaluate(
        expression: String,
        leaves: List<() -> Boolean>,
    ): Boolean =
        ExpressionEvaluator(
            context = leaves,
            predicates = leaves.indices.map(::LeafPredicate),
            maxExpressionLength = Int.MAX_VALUE,
        ).apply { leaves.indices.forEach { markBarePredicate(leaf(it)) } }
            .evaluate(expression)

    private class LeafPredicate(
        private val index: Int,
    ) : ConditionPredicate<List<() -> Boolean>> {
        override val name = leaf(index)
        override val arity = 0
        override val isBarePredicate = true

        override fun evaluate(
            args: List<String>,
            context: List<() -> Boolean>,
        ): Boolean = context[index]()
    }
}
