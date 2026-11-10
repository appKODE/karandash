package io.karandash.parser

import io.karandash.api.exception.ConfigParseException
import io.karandash.conditions.AtomExpression
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.parser.ast.ComparisonExpr
import io.karandash.parser.ast.Expression
import io.karandash.parser.ast.FieldAccessExpr
import io.karandash.parser.ast.FunctionCallExpr
import io.karandash.parser.ast.IfStatement
import io.karandash.parser.ast.LiteralExpr
import io.karandash.parser.ast.LogicalExpr
import io.karandash.parser.ast.NotExpr
import io.karandash.parser.ast.Statement
import io.karandash.platform.PlatformEnvironment

/**
 * Evaluates KTS DSL expressions for [KtsJsonInterpreter]. `env.VAR` values become `"${VAR}"` placeholders.
 *
 * @param envDefaults the config's own `env { }` block, read by `if` conditions for variables the environment leaves unset.
 */
internal class ExpressionEvaluator(
    private val envDefaults: Map<String, String>,
) {
    fun evalString(expr: Expression): String =
        when (expr) {
            is LiteralExpr -> {
                val value = expr.value.toString()
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value.substring(1, value.length - 1)
                } else {
                    value
                }
            }

            is FieldAccessExpr -> {
                resolveFieldAccess(expr)
            }

            else -> {
                throw ConfigParseException("Expected string literal, got $expr")
            }
        }

    fun evalBoolean(expr: Expression): Boolean =
        when (expr) {
            is LiteralExpr -> when (expr.value) {
                is Boolean -> expr.value
                "true" -> true
                "false" -> false
                else -> throw ConfigParseException("Expected boolean literal, got ${expr.value}")
            }

            else -> throw ConfigParseException("Expected boolean literal, got $expr")
        }

    /**
     * Evaluates an `if` condition on the core conditions engine: `&&`, `||` and `!` are rendered around
     * [AtomExpression.leaf] references, one per comparison, literal or `env()` call.
     */
    fun evalCondition(expr: Expression): Boolean {
        val leaves = mutableListOf<() -> Boolean>()
        val rendered = render(expr, leaves)
        return try {
            AtomExpression.evaluate(rendered, leaves)
        } catch (e: IllegalArgumentException) {
            throw ConfigParseException("Unsupported condition: ${e.message}")
        }
    }

    private fun render(
        expr: Expression,
        leaves: MutableList<() -> Boolean>,
    ): String =
        when (expr) {
            is LogicalExpr -> {
                when (expr.op) {
                    // `&&` binds tighter than `||` in the engine, so only an `||` operand needs parentheses
                    "&&" -> "${operand(expr.left, leaves)} && ${operand(expr.right, leaves)}"

                    "||" -> "${render(expr.left, leaves)} || ${render(expr.right, leaves)}"

                    else -> throw ConfigParseException("Unsupported logical operator: ${expr.op}")
                }
            }

            is NotExpr -> {
                val operand = render(expr.operand, leaves)
                if (expr.operand is LogicalExpr) "!($operand)" else "!$operand"
            }

            else -> {
                leaves += { evalLeaf(expr) }
                AtomExpression.leaf(leaves.size - 1)
            }
        }

    private fun operand(
        expr: Expression,
        leaves: MutableList<() -> Boolean>,
    ): String = if (expr is LogicalExpr && expr.op == "||") "(${render(expr, leaves)})" else render(expr, leaves)

    private fun evalLeaf(expr: Expression): Boolean =
        when (expr) {
            is LiteralExpr -> {
                evalBoolean(expr)
            }

            is ComparisonExpr -> {
                val left = evalConditionOperand(expr.left)
                val right = evalConditionOperand(expr.right)
                when (expr.op) {
                    "==" -> left == right
                    "!=" -> left != right
                    else -> false
                }
            }

            is FunctionCallExpr -> {
                if (expr.name == "env" && expr.args.size == 1) {
                    conditionEnv(evalString(expr.args[0])) != null
                } else {
                    throw ConfigParseException("Unsupported function in condition: ${expr.name}")
                }
            }

            else -> {
                throw ConfigParseException("Unsupported condition: $expr")
            }
        }

    /**
     * Evaluates an [IfStatement] condition and returns the statements from the
     * matching branch, or null if no branch applies.
     */
    fun evaluateIfBranch(ifStmt: IfStatement): List<Statement>? {
        val condResult = evalCondition(ifStmt.condition)
        return when {
            condResult -> ifStmt.thenBlock.statements
            ifStmt.elseBlock != null -> ifStmt.elseBlock.statements
            else -> null
        }
    }

    private fun evalConditionOperand(expr: Expression): Any? =
        when (expr) {
            is LiteralExpr -> {
                val v = expr.value
                if (v == "null") null else v
            }

            is FunctionCallExpr -> {
                if (expr.name == "env" && expr.args.size == 1) {
                    conditionEnv(evalString(expr.args[0]))
                } else {
                    throw ConfigParseException("Unsupported function in condition: ${expr.name}")
                }
            }

            is FieldAccessExpr -> {
                if (expr.path.size == 2 && expr.path[0] == "env") conditionEnv(expr.path[1]) else evalString(expr)
            }

            else -> {
                evalString(expr)
            }
        }

    /**
     * Env lookup for `if` conditions, which pick a branch at parse time and so need the value now. Resolves like
     * `${VAR}` interpolation: a denied var is null, otherwise the environment, then the `env { }` default.
     */
    private fun conditionEnv(varName: String): String? =
        if (varName in KarandashEnvSecurity.DENYLIST) null else PlatformEnvironment.getEnv(varName) ?: envDefaults[varName]

    private fun resolveFieldAccess(expr: FieldAccessExpr): String {
        if (expr.path.size == 2 && expr.path[0] == "env") return "\${${expr.path[1]}}"
        throw ConfigParseException("Unsupported field access: ${expr.path.joinToString(".")}")
    }
}
