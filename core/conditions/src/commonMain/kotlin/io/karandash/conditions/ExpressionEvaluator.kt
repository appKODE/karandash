package io.karandash.conditions

import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.config.KarandashLimits
import io.karandash.helpers.RegexHelper
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.Serializable

/**
 * Generic recursive-descent expression evaluator with pluggable predicates.
 *
 * Supports a condition expression language with:
 * - Function-call predicates: `name(args...)`, bare predicates: `is_draft`
 * - Comparison predicates: `change_count > 5`, `additions >= 10`
 * - Boolean literals: `true`, `false` (case-insensitive)
 * - Logical operators: `&&`, `||`, `not()`, `!` prefix (`!is_ci`, `!(a || b)`), parentheses
 *
 * Predicate registration is safe from several threads (copy-on-write registries); instances still cache compiled regexes internally.
 *
 * @param C The context type that predicates evaluate against.
 * @param context The evaluation context passed to all predicates.
 * @param predicates Initial predicates to register.
 * @param sharedRegexCache Optional shared regex cache.
 * @param maxExpressionLength Maximum allowed expression length.
 * @param maxNestingDepth Maximum parenthesis nesting depth.
 * @param maxRegexLength Maximum regex pattern length in predicate arguments.
 */
class ExpressionEvaluator<C>(
    private val context: C,
    predicates: List<ConditionPredicate<C>> = emptyList(),
    sharedRegexCache: TieredCache<String, Regex>? = null,
    private val patternExpander: ((String) -> String)? = null,
    private val maxExpressionLength: Int = KarandashLimits.MAX_EXPRESSION_LENGTH,
    private val maxNestingDepth: Int = KarandashLimits.MAX_NESTING_DEPTH,
    private val maxRegexLength: Int = KarandashLimits.MAX_REGEX_LENGTH,
) {
    private val regexCache = sharedRegexCache ?: TieredCache(
        name = "condition-regex-local",
        maxEntries = MAX_CONDITION_REGEX_CACHE,
        tier = CacheTier.MEMORY_ONLY,
    )

    /** A predicate and its parse flags, swapped in as one value so a reader never sees one without the other. */
    private class Registered<C>(
        val predicate: ConditionPredicate<C>,
        val regex: Boolean = false,
        val comparison: Boolean = false,
        val bare: Boolean = false,
    ) {
        fun withFlags(
            regex: Boolean = this.regex,
            comparison: Boolean = this.comparison,
            bare: Boolean = this.bare,
        ) = Registered(predicate, regex, comparison, bare)
    }

    private val registry = atomic<Map<String, Registered<C>>>(predicates.associate { it.name to Registered(it) })

    /**
     * Registered operator-syntax fields.
     *
     * Maps a field name (e.g., `"title"`) to the three predicate names that
     * implement `*=` (glob), `~=` (regex), and `=` (exact) for that field.
     * Populated via [registerOperatorField].
     */
    private val operatorFields = atomic<Map<String, OperatorPredicateNames>>(emptyMap())

    /**
     * Registers a custom [ConditionPredicate], making it available in expressions.
     * If a predicate with the same name already exists, it is replaced.
     */
    fun registerPredicate(predicate: ConditionPredicate<C>) {
        // A replaced predicate keeps the flags already marked for its name.
        registry.update {
            it +
                (
                    predicate.name to
                        Registered(
                            predicate,
                            it[predicate.name]?.regex == true,
                            it[predicate.name]?.comparison == true,
                            it[predicate.name]?.bare == true
                        )
                )
        }
    }

    /**
     * Registers all predicates from a [PredicateRegistry], auto-detecting metadata
     * ([isRegexPredicate], [isComparisonPredicate][ConditionPredicate.isComparisonPredicate],
     * [isBarePredicate][ConditionPredicate.isBarePredicate]) from each predicate's properties.
     * Each predicate and its flags are registered in one atomic step.
     */
    fun registerAll(registry: PredicateRegistry<C>) {
        for (predicate in registry.all()) {
            this.registry.update {
                it + (
                    predicate.name to Registered(
                        predicate,
                        regex = predicate.isRegexPredicate || it[predicate.name]?.regex == true,
                        comparison = predicate.isComparisonPredicate || it[predicate.name]?.comparison == true,
                        bare = predicate.isBarePredicate || it[predicate.name]?.bare == true,
                    )
                )
            }
        }
    }

    /** Marks a registered predicate as requiring regex argument validation. */
    fun markRegexPredicate(name: String) {
        registry.update { m -> m[name]?.let { m + (name to it.withFlags(regex = true)) } ?: m }
    }

    /** Marks a registered predicate as using comparison operators (e.g., `name > value`). */
    fun markComparisonPredicate(name: String) {
        registry.update { m -> m[name]?.let { m + (name to it.withFlags(comparison = true)) } ?: m }
    }

    /** Marks a registered predicate as bare (no parentheses required). */
    fun markBarePredicate(name: String) {
        registry.update { m -> m[name]?.let { m + (name to it.withFlags(bare = true)) } ?: m }
    }

    /**
     * Registers a field name for operator syntax, mapping it to its glob, regex,
     * and exact-match predicate names.
     *
     * After registration, expressions like `title *= "feat:*"`, `title ~= "^feat"`,
     * and `title = "exact"` desugar to the corresponding predicate function call.
     */
    fun registerOperatorField(
        fieldName: String,
        names: OperatorPredicateNames,
    ) {
        operatorFields.update { it + (fieldName to names) }
    }

    /**
     * Evaluates [expression] and returns `true` if the condition is met.
     *
     * @param expression Condition expression string.
     * @return `true` if the condition evaluates to true.
     * @throws IllegalArgumentException if the expression is malformed or too long.
     */
    fun evaluate(expression: String): Boolean = eval(parseChecked(expression))

    /**
     * Parses [expression] without evaluating it: throws what [evaluate] would throw for an expression that is too
     * long, nested too deep, malformed or names an unknown predicate.
     *
     * @throws IllegalArgumentException if the expression is malformed or too long.
     */
    fun validate(expression: String) {
        parseChecked(expression)
    }

    private fun parseChecked(expression: String): Node {
        require(expression.length <= maxExpressionLength) {
            "Condition expression too long (${expression.length} > $maxExpressionLength chars)"
        }
        require(maxParenDepth(expression) <= maxNestingDepth) {
            "Condition expression nesting too deep (max: $maxNestingDepth levels)"
        }
        return parse(expression.trim())
    }

    /** Returns a compiled [Regex] from the shared cache. */
    fun cachedRegex(pattern: String): Regex =
        regexCache.getOrCompute(pattern) {
            // Invalid patterns produce a never-matching regex so the predicate safely returns false.
            RegexHelper.compileOrNull(pattern) ?: Regex("(?!)")
        }

    private fun maxParenDepth(expr: String): Int {
        var depth = 0
        var max = 0
        for (c in expr) {
            if (c == '(') {
                depth++
                if (depth > max) max = depth
            } else if (c == ')') {
                depth--
            }
        }
        return max
    }

    // ── AST ──────────────────────────────────────────────────────────────

    @Serializable
    private sealed class Node {
        @Serializable
        data class And(
            val children: List<Node>
        ) : Node()

        @Serializable
        data class Or(
            val children: List<Node>
        ) : Node()

        @Serializable
        data class Not(
            val child: Node
        ) : Node()

        @Serializable
        data class Literal(
            val value: Boolean
        ) : Node()

        @Serializable
        data class Predicate(
            val name: String,
            val args: List<String>
        ) : Node()
    }

    enum class CompOp { GT, GTE, LT, LTE, EQ }

    /**
     * Operator tokens for field-operator-value condition syntax.
     *
     * - [GLOB]: `field *= "pattern"` - glob match against field value
     * - [REGEX]: `field ~= "pattern"` - regex match against field value
     * - [EXACT]: `field = "value"` - exact string match against field value
     */
    enum class FieldOp { GLOB, REGEX, EXACT }

    /**
     * Predicate names to invoke for each [FieldOp] when a field is referenced via
     * operator syntax (e.g., `title *= "feat:*"`).
     *
     * @param globName Predicate name for the `*=` glob-match operator.
     * @param regexName Predicate name for the `~=` regex-match operator.
     * @param exactName Predicate name for the `=` exact-match operator.
     */
    @Serializable
    data class OperatorPredicateNames(
        val globName: String,
        val regexName: String,
        val exactName: String,
    )

    // ── Parser (recursive descent) ───────────────────────────────────────

    private fun parse(input: String): Node {
        val trimmed = input.trim()

        // Boolean literals: `true` always matches, `false` never does.
        trimmed.lowercase().toBooleanStrictOrNull()?.let { return Node.Literal(it) }

        if (trimmed.startsWith("(") && trimmed.endsWith(")") && isBalancedOuter(trimmed)) {
            return parse(trimmed.substring(1, trimmed.length - 1))
        }

        splitAtTopLevel(trimmed, " || ")?.let { parts ->
            return Node.Or(parts.map { parse(it) })
        }

        splitAtTopLevel(trimmed, " && ")?.let { parts ->
            return Node.And(parts.map { parse(it) })
        }

        if (trimmed.startsWith("not(") && trimmed.endsWith(")")) {
            val inner = extractFunctionContent(trimmed, "not")
            return Node.Not(parse(inner))
        }

        if (trimmed.startsWith("!")) {
            return Node.Not(parse(trimmed.substring(1)))
        }

        return parsePredicate(trimmed)
    }

    private fun parsePredicate(expr: String): Node {
        // Operator-syntax predicates: `field *= "glob"`, `field ~= "regex"`, `field = "exact"`
        parseOperatorSyntax(expr)?.let { return it }

        // Bare predicates without parentheses
        if (registry.value[expr]?.bare == true) return Node.Predicate(expr, listOf("true"))

        // Comparison predicates: "change_count > 5", "additions >= 10"
        // Sort by length descending so longer names match first
        // (e.g., "file_count_added" before "file_count")
        for (name in registry.value
            .filterValues { it.comparison }
            .keys
            .sortedByDescending { it.length }) {
            if (expr.startsWith(name) &&
                !expr.getOrNull(name.length).isIdentifierChar() &&
                !expr.substring(name.length).trimStart().startsWith("(")
            ) {
                val (op, value) = parseComparisonOpAndValue(expr, name)
                return Node.Predicate(name, listOf(op.name, value.toString()))
            }
        }

        // Function-call predicates: "name(args...)"
        val parenIdx = expr.indexOf('(')
        if (parenIdx < 0) error("Unknown condition: $expr")
        val name = expr.substring(0, parenIdx)
        val entry = checkNotNull(registry.value[name]) { "Unknown condition: $expr" }

        // Regex-argument predicates need length validation
        if (entry.regex) {
            val args = extractArgs(expr, name)
            args.forEach { validateRegexLength(it) }
            requireArity(name, args, expr)
            return Node.Predicate(name, args)
        }

        // Multi-arg predicates
        val args = if (expr.indexOf(',', parenIdx) >= 0) {
            extractArgs(expr, name)
        } else {
            listOf(extractStringArg(expr, name))
        }
        if (entry.comparison) {
            require(compOpOrNull(args.firstOrNull()) != null) {
                "Invalid comparison operator '${args.firstOrNull()}' in '$expr'; " +
                    "expected one of ${CompOp.entries.joinToString()}"
            }
        }
        requireArity(name, args, expr)
        return Node.Predicate(name, args)
    }

    /** A function-call predicate gets exactly [ConditionPredicate.arity] arguments (any count when negative). */
    private fun requireArity(
        name: String,
        args: List<String>,
        expr: String,
    ) {
        val arity = registry.value
            .getValue(name)
            .predicate.arity
        require(arity < 0 || args.size == arity) {
            "'$name' takes $arity argument(s), got ${args.size} in '$expr'; quote an argument that contains a comma"
        }
    }

    private fun parseComparisonOpAndValue(
        expr: String,
        prefix: String,
    ): Pair<CompOp, Int> {
        val rest = expr.removePrefix(prefix).trim()
        val op = when {
            rest.startsWith(">=") -> CompOp.GTE
            rest.startsWith(">") -> CompOp.GT
            rest.startsWith("<=") -> CompOp.LTE
            rest.startsWith("<") -> CompOp.LT
            rest.startsWith("==") -> CompOp.EQ
            else -> error("Expected comparison operator after '$prefix', got: $rest")
        }
        val numStr = rest
            .removePrefix(
                when (op) {
                    CompOp.GTE -> ">="
                    CompOp.GT -> ">"
                    CompOp.LTE -> "<="
                    CompOp.LT -> "<"
                    CompOp.EQ -> "=="
                },
            ).trim()
        val value = numStr.toIntOrNull() ?: error("Expected integer after operator in '$expr'")
        return Pair(op, value)
    }

    // ── Evaluator ────────────────────────────────────────────────────────

    private fun eval(node: Node): Boolean =
        when (node) {
            is Node.And -> {
                node.children.all { eval(it) }
            }

            is Node.Or -> {
                node.children.any { eval(it) }
            }

            is Node.Not -> {
                !eval(node.child)
            }

            is Node.Literal -> {
                node.value
            }

            is Node.Predicate -> {
                val predicate = registry.value[node.name]?.predicate
                    ?: error("Unknown condition: ${node.name}")
                val args = expandPatternArgs(node.args)
                predicate.evaluate(args, context)
            }
        }

    /**
     * Expands `@`-prefixed pattern references in predicate arguments using the
     * optional [patternExpander]. Non-pattern arguments pass through unchanged.
     */
    private fun expandPatternArgs(args: List<String>): List<String> {
        val expander = patternExpander ?: return args
        return args.map { arg ->
            if (arg.startsWith("@")) expander(arg) else arg
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun isBalancedOuter(input: String): Boolean {
        var depth = 0
        for (i in 0 until input.length - 1) {
            when (input[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0) return false
        }
        return true
    }

    private fun splitAtTopLevel(
        input: String,
        delimiter: String,
    ): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i <= input.length - delimiter.length) {
            when (input[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && input.startsWith(delimiter, i)) {
                parts.add(input.substring(start, i).trim())
                start = i + delimiter.length
                i += delimiter.length
            } else {
                i++
            }
        }
        parts.add(input.substring(start).trim())
        return if (parts.size > 1) parts else null
    }

    private fun extractStringArg(
        expression: String,
        functionName: String,
    ): String {
        val content = extractFunctionContent(expression, functionName)
        return content.trim().removeSurrounding("\"").removeSurrounding("'")
    }

    private fun extractArgs(
        expression: String,
        functionName: String,
    ): List<String> {
        val content = extractFunctionContent(expression, functionName)
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote: Char? = null
        for (c in content) {
            when {
                inQuote != null -> {
                    if (c == inQuote) inQuote = null else current.append(c)
                }

                c == '"' || c == '\'' -> {
                    inQuote = c
                }

                c == ',' -> {
                    args.add(current.toString().trim())
                    current.clear()
                }

                else -> {
                    current.append(c)
                }
            }
        }
        args.add(current.toString().trim())
        return args
    }

    private fun extractFunctionContent(
        expression: String,
        functionName: String,
    ): String {
        val afterFunction = expression.substringAfter("$functionName(")
        var depth = 1
        var endIndex = 0
        for (i in afterFunction.indices) {
            when (afterFunction[i]) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                    if (depth == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }
        return afterFunction.substring(0, endIndex)
    }

    /**
     * Parses operator-syntax expressions of the form `field OP "value"`.
     *
     * Supported operators (checked in order - `*=` and `~=` before `=` to avoid
     * the shorter token matching as a prefix):
     * - `*=` → [FieldOp.GLOB]
     * - `~=` → [FieldOp.REGEX]
     * - `=`  → [FieldOp.EXACT]
     *
     * Returns `null` if the expression does not match any registered operator field.
     */
    private fun parseOperatorSyntax(expr: String): Node? {
        for ((field, names) in operatorFields.value) {
            val (op, predicateName) = when {
                expr.startsWith("$field *= ") -> Pair(FieldOp.GLOB, names.globName)
                expr.startsWith("$field ~= ") -> Pair(FieldOp.REGEX, names.regexName)
                expr.startsWith("$field = ") -> Pair(FieldOp.EXACT, names.exactName)
                else -> continue
            }
            val rawValue = expr
                .substringAfter(
                    when (op) {
                        FieldOp.GLOB -> "$field *= "
                        FieldOp.REGEX -> "$field ~= "
                        FieldOp.EXACT -> "$field = "
                    },
                ).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (op == FieldOp.REGEX) validateRegexLength(rawValue)
            return Node.Predicate(predicateName, listOf(rawValue))
        }
        return null
    }

    private fun validateRegexLength(pattern: String) {
        require(pattern.length <= maxRegexLength) {
            "Regex pattern too long (${pattern.length} > $maxRegexLength chars)"
        }
    }

    companion object {
        /** Cache name for use with [io.karandash.cache.CacheManager]. */
        const val CACHE_NAME = "condition-regex"
        private const val MAX_CONDITION_REGEX_CACHE = 256

        /**
         * Creates a [CacheManager]-managed regex cache suitable for passing as
         * the `sharedRegexCache` constructor parameter.
         *
         * Use this in the validation pipeline so the condition regex cache
         * participates in central stats, invalidation, and lifecycle management.
         */
        fun createManagedRegexCache(cacheManager: CacheManager): TieredCache<String, Regex> =
            cacheManager.getOrCreate(
                CACHE_NAME,
                CacheSpec(maxEntries = MAX_CONDITION_REGEX_CACHE),
            )

        /**
         * Compares two integers using the given [CompOp].
         * Public for use by comparison predicates.
         */
        fun compareInt(
            actual: Int,
            op: CompOp,
            expected: Int,
        ): Boolean =
            when (op) {
                CompOp.GT -> actual > expected
                CompOp.GTE -> actual >= expected
                CompOp.LT -> actual < expected
                CompOp.LTE -> actual <= expected
                CompOp.EQ -> actual == expected
            }
    }
}

/** Returns `true` if the character is a letter, digit, or underscore - i.e., valid in a predicate identifier. */
private fun Char?.isIdentifierChar(): Boolean = this != null && (isLetterOrDigit() || this == '_')

/**
 * Parses a [ExpressionEvaluator.CompOp] name; `null` when [raw] is not one. Single parse point for every
 * comparison predicate. The evaluator rejects an unknown operator at parse time, so callers report it as a
 * malformed condition instead of the predicate silently evaluating to `false`.
 */
internal fun compOpOrNull(raw: String?): ExpressionEvaluator.CompOp? = ExpressionEvaluator.CompOp.entries.firstOrNull { it.name == raw }
