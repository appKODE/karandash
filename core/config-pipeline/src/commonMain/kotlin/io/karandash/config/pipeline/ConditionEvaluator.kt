package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.conditions.AtomExpression
import io.karandash.config.KarandashLimits
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.platform.PlatformEnvironment
import kotlinx.serialization.Serializable

/**
 * Evaluates conditional expressions for extends entries.
 *
 * Supports:
 * - Environment variable substitution: `${VAR_NAME}`
 * - String literals: `"value"` or `'value'`
 * - Identifiers: `platform`, `CI`, etc.
 * - Comparisons: `==`, `!=`
 * - Logical operators: `&&`, `||`, `!`
 * - Parentheses for grouping: `(expr)`
 *
 * The boolean structure runs on the core conditions engine through [AtomExpression], with its precedence and
 * nesting limit; the condition text is limited to [KarandashLimits.MAX_EXPRESSION_LENGTH] like a rule condition.
 *
 * Examples:
 * - `"${ENV} == 'production'"` - check if ENV var equals "production"
 * - `"platform == 'gitlab'"` - check if platform name equals "gitlab"
 * - `"${CI} && ${ENV} == 'prod'"` - AND logic with truthy check
 * - `"!${DEBUG}"` - NOT logic (truthy check)
 *
 * @property envProvider Function to retrieve environment variable values (defaults to PlatformEnvironment.getEnv, with
 *   [KarandashEnvSecurity.DENYLIST] variables reading as unset)
 * @property platformName Optional platform name for `platform` identifier resolution
 */
class ConditionEvaluator(
    private val envProvider: (String) -> String? = { name ->
        if (name in KarandashEnvSecurity.DENYLIST) null else PlatformEnvironment.getEnv(name)
    },
    private val platformName: String? = null
) {
    /**
     * Evaluate a conditional expression to a boolean result.
     *
     * @param condition The condition string to evaluate
     * @return true if condition evaluates to true, false otherwise
     * @throws ConfigException with E0016 error code if expression is invalid
     */
    fun evaluate(condition: String): Boolean =
        try {
            require(condition.length <= KarandashLimits.MAX_EXPRESSION_LENGTH) {
                "Condition expression too long (${condition.length} > ${KarandashLimits.MAX_EXPRESSION_LENGTH} chars)"
            }
            val renderer = Renderer(tokenize(condition), envProvider, platformName)
            AtomExpression.evaluate(renderer.render(), renderer.leaves)
        } catch (e: IllegalStateException) {
            throw ConfigException(
                "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Failed to evaluate extends condition '$condition': ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            throw ConfigException(
                "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Failed to evaluate extends condition '$condition': ${e.message}"
            )
        }

    /**
     * Tokenize the condition string into a list of tokens.
     */
    private fun tokenize(condition: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        while (i < condition.length) {
            when {
                condition[i].isWhitespace() -> {
                    i++
                }

                // Check for ${...} env var substitution
                condition.startsWith("\${", i) -> {
                    val endIndex = condition.indexOf('}', i + 2)
                    if (endIndex == -1) {
                        throw ConfigException(
                            "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Failed to evaluate extends condition '$condition': Unclosed environment variable substitution"
                        )
                    }
                    val varName = condition.substring(i + 2, endIndex)
                    tokens.add(Token.EnvVar(varName))
                    i = endIndex + 1
                }

                // String literals with double or single quotes
                condition[i] == '"' || condition[i] == '\'' -> {
                    val quote = condition[i]
                    val endIndex = condition.indexOf(quote, i + 1)
                    if (endIndex == -1) {
                        throw ConfigException(
                            "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Failed to evaluate extends condition '$condition': Unclosed string literal"
                        )
                    }
                    val value = condition.substring(i + 1, endIndex)
                    tokens.add(Token.StringLiteral(value))
                    i = endIndex + 1
                }

                // Two-character operators
                condition.startsWith("==", i) -> {
                    tokens.add(Token.Operator("=="))
                    i += 2
                }

                condition.startsWith("!=", i) -> {
                    tokens.add(Token.Operator("!="))
                    i += 2
                }

                condition.startsWith("&&", i) -> {
                    tokens.add(Token.Operator("&&"))
                    i += 2
                }

                condition.startsWith("||", i) -> {
                    tokens.add(Token.Operator("||"))
                    i += 2
                }

                // Single-character operators
                condition[i] == '!' -> {
                    tokens.add(Token.Operator("!"))
                    i++
                }

                condition[i] == '(' -> {
                    tokens.add(Token.LeftParen)
                    i++
                }

                condition[i] == ')' -> {
                    tokens.add(Token.RightParen)
                    i++
                }

                // Identifiers (variable names like 'platform', 'CI', etc.)
                condition[i].isLetter() || condition[i] == '_' -> {
                    val start = i
                    while (i < condition.length && (condition[i].isLetterOrDigit() || condition[i] == '_')) {
                        i++
                    }
                    val identifier = condition.substring(start, i)
                    tokens.add(Token.Identifier(identifier))
                }

                else -> {
                    throw ConfigException(
                        "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Failed to evaluate extends condition '$condition': Unexpected character '${condition[i]}' at position $i"
                    )
                }
            }
        }

        return tokens
    }

    /**
     * Token types for the lexer.
     */
    private sealed class Token {
        @Serializable
        data class EnvVar(
            val name: String
        ) : Token()

        @Serializable
        data class StringLiteral(
            val value: String
        ) : Token()

        @Serializable
        data class Identifier(
            val name: String
        ) : Token()

        @Serializable
        data class Operator(
            val op: String
        ) : Token()

        object LeftParen : Token()

        object RightParen : Token()
    }

    /**
     * Renders the tokens for the core conditions engine: `&&`, `||`, `!` and parentheses pass through, each
     * comparison (`a == b`, `a != b`) or truthy operand becomes an [AtomExpression.leaf] evaluated against
     * [envProvider] and [platformName]. Tokens that do not form a whole expression fail with [IllegalStateException].
     */
    private class Renderer(
        private val tokens: List<Token>,
        private val envProvider: (String) -> String?,
        private val platformName: String?,
    ) {
        val leaves = mutableListOf<() -> Boolean>()

        fun render(): String {
            val out = StringBuilder()
            var i = 0
            var depth = 0
            var expectOperand = true
            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    expectOperand && token == Token.LeftParen -> {
                        out.append('(')
                        depth++
                    }

                    expectOperand && token == Token.Operator("!") -> {
                        out.append('!')
                    }

                    expectOperand && token.isValue() -> {
                        i = renderLeaf(i, out)
                        expectOperand = false
                    }

                    !expectOperand && token == Token.RightParen && depth > 0 -> {
                        out.append(')')
                        depth--
                    }

                    !expectOperand && (token == Token.Operator("&&") || token == Token.Operator("||")) -> {
                        out.append(" ${(token as Token.Operator).op} ")
                        expectOperand = true
                    }

                    else -> {
                        error("Unexpected token: $token")
                    }
                }
                i++
            }
            check(!expectOperand && depth == 0) { if (depth > 0) "Expected ')'" else "Unexpected end of condition" }
            return out.toString()
        }

        /** Appends the leaf starting at token [start] and returns the index of its last token. */
        private fun renderLeaf(
            start: Int,
            out: StringBuilder,
        ): Int {
            val left = tokens[start]
            val op = (tokens.getOrNull(start + 1) as? Token.Operator)?.op?.takeIf { it == "==" || it == "!=" }
            if (op == null) {
                leaves += { isTruthy(value(left)) }
            } else {
                val right = tokens.getOrNull(start + 2)
                check(right != null && right.isValue()) { "Unexpected token: $right" }
                leaves += { (value(left) == value(right)) == (op == "==") }
            }
            out.append(AtomExpression.leaf(leaves.size - 1))
            return if (op == null) start else start + 2
        }

        private fun Token.isValue(): Boolean = this is Token.EnvVar || this is Token.StringLiteral || this is Token.Identifier

        private fun value(token: Token): String =
            when (token) {
                is Token.EnvVar -> envProvider(token.name) ?: ""

                is Token.StringLiteral -> token.value

                // `platform` names the platform; any other identifier is an environment variable (without ${...})
                is Token.Identifier -> if (token.name == "platform") platformName ?: "" else envProvider(token.name) ?: ""

                else -> error("Unexpected token: $token")
            }

        private fun isTruthy(value: String): Boolean =
            when (value.lowercase()) {
                "", "false", "0", "no", "off" -> false
                else -> true
            }
    }
}
