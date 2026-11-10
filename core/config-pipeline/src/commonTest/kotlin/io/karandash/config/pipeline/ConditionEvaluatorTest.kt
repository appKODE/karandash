package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.config.KarandashLimits
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionEvaluatorTest {
    @Test
    fun `evaluate simple env var equality - true`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "ENV") "production" else null }
        )
        assertTrue(evaluator.evaluate("\${ENV} == 'production'"))
    }

    @Test
    fun `evaluate simple env var equality - false`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "ENV") "development" else null }
        )
        assertFalse(evaluator.evaluate("\${ENV} == 'production'"))
    }

    @Test
    fun `evaluate env var inequality - true`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "ENV") "development" else null }
        )
        assertTrue(evaluator.evaluate("\${ENV} != 'production'"))
    }

    @Test
    fun `evaluate env var inequality - false`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "ENV") "production" else null }
        )
        assertFalse(evaluator.evaluate("\${ENV} != 'production'"))
    }

    @Test
    fun `evaluate platform equality - true`() {
        val evaluator = ConditionEvaluator(
            platformName = "gitlab"
        )
        assertTrue(evaluator.evaluate("platform == 'gitlab'"))
    }

    @Test
    fun `evaluate platform equality - false`() {
        val evaluator = ConditionEvaluator(
            platformName = "github"
        )
        assertFalse(evaluator.evaluate("platform == 'gitlab'"))
    }

    @Test
    fun `evaluate AND with both true`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "x"
                    "B" -> "y"
                    else -> null
                }
            }
        )
        assertTrue(evaluator.evaluate("\${A} == 'x' && \${B} == 'y'"))
    }

    @Test
    fun `evaluate AND with one false`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "x"
                    "B" -> "z"
                    else -> null
                }
            }
        )
        assertFalse(evaluator.evaluate("\${A} == 'x' && \${B} == 'y'"))
    }

    @Test
    fun `evaluate OR with both true`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "x"
                    "B" -> "y"
                    else -> null
                }
            }
        )
        assertTrue(evaluator.evaluate("\${A} == 'x' || \${B} == 'y'"))
    }

    @Test
    fun `evaluate OR with one true`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "x"
                    "B" -> "z"
                    else -> null
                }
            }
        )
        assertTrue(evaluator.evaluate("\${A} == 'x' || \${B} == 'y'"))
    }

    @Test
    fun `evaluate OR with both false`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "w"
                    "B" -> "z"
                    else -> null
                }
            }
        )
        assertFalse(evaluator.evaluate("\${A} == 'x' || \${B} == 'y'"))
    }

    @Test
    fun `evaluate NOT with true value`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "true" else null }
        )
        assertFalse(evaluator.evaluate("!\${CI}"))
    }

    @Test
    fun `evaluate NOT with false value`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "" else null }
        )
        assertTrue(evaluator.evaluate("!\${CI}"))
    }

    @Test
    fun `evaluate truthy check for non-empty string`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "1" else null }
        )
        assertTrue(evaluator.evaluate("\${CI}"))
    }

    @Test
    fun `evaluate truthy check for empty string`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "" else null }
        )
        assertFalse(evaluator.evaluate("\${CI}"))
    }

    @Test
    fun `evaluate truthy check for false literal`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "false" else null }
        )
        assertFalse(evaluator.evaluate("\${CI}"))
    }

    @Test
    fun `evaluate truthy check for 0 literal`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "0" else null }
        )
        assertFalse(evaluator.evaluate("\${CI}"))
    }

    @Test
    fun `evaluate parentheses for grouping`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "x"
                    "B" -> "y"
                    "C" -> "z"
                    else -> null
                }
            }
        )
        assertTrue(evaluator.evaluate("(\${A} == 'x' || \${B} == 'w') && \${C} == 'z'"))
    }

    @Test
    fun `evaluate complex expression with parentheses`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "ENV" -> "prod"
                    "CI" -> "true"
                    "DEBUG" -> ""
                    else -> null
                }
            },
            platformName = "gitlab"
        )
        assertTrue(evaluator.evaluate("platform == 'gitlab' && (\${ENV} == 'prod' || \${ENV} == 'staging') && \${CI} && !\${DEBUG}"))
    }

    @Test
    fun `evaluate missing env var treated as empty string`() {
        val evaluator = ConditionEvaluator(
            envProvider = { null }
        )
        assertTrue(evaluator.evaluate("\${MISSING} == ''"))
    }

    @Test
    fun `evaluate double quotes for string literals`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "ENV") "production" else null }
        )
        assertTrue(evaluator.evaluate("\${ENV} == \"production\""))
    }

    @Test
    fun `evaluate identifier without dollar sign as env var`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "CI") "true" else null }
        )
        assertTrue(evaluator.evaluate("CI"))
    }

    @Test
    fun `evaluate platform identifier without quotes`() {
        val evaluator = ConditionEvaluator(
            platformName = "gitlab"
        )
        assertTrue(evaluator.evaluate("platform == 'gitlab'"))
    }

    @Test
    fun `throw ConfigException for unclosed string literal`() {
        val evaluator = ConditionEvaluator()
        val exception = assertFailsWith<ConfigException> {
            evaluator.evaluate("\${ENV} == 'unclosed")
        }
        assertTrue(exception.message?.contains("E0016") == true)
        assertTrue(exception.message?.contains("Unclosed string literal") == true)
    }

    @Test
    fun `throw ConfigException for unclosed env var substitution`() {
        val evaluator = ConditionEvaluator()
        val exception = assertFailsWith<ConfigException> {
            evaluator.evaluate("\${ENV == 'value'")
        }
        assertTrue(exception.message?.contains("E0016") == true)
        assertTrue(exception.message?.contains("Unclosed environment variable") == true)
    }

    @Test
    fun `throw ConfigException for unexpected character`() {
        val evaluator = ConditionEvaluator()
        val exception = assertFailsWith<ConfigException> {
            evaluator.evaluate("\${ENV} @ 'value'")
        }
        assertTrue(exception.message?.contains("E0016") == true)
        assertTrue(exception.message?.contains("Unexpected character") == true)
    }

    @Test
    fun `throw ConfigException for missing closing paren`() {
        val evaluator = ConditionEvaluator()
        val exception = assertFailsWith<ConfigException> {
            evaluator.evaluate("(\${ENV} == 'value'")
        }
        assertTrue(exception.message?.contains("E0016") == true)
    }

    @Test
    fun `evaluate whitespace handling`() {
        val evaluator = ConditionEvaluator(
            envProvider = { if (it == "ENV") "production" else null }
        )
        assertTrue(evaluator.evaluate("  \${ENV}   ==   'production'  "))
    }

    @Test
    fun `evaluate operator precedence - AND before OR`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "A" -> "true"
                    "B" -> ""
                    "C" -> "true"
                    else -> null
                }
            }
        )
        // Should evaluate as: A || (B && C)
        // A is truthy, so result should be true
        assertTrue(evaluator.evaluate("\${A} || \${B} && \${C}"))
    }

    @Test
    fun `evaluate with no env provider uses default`() {
        val evaluator = ConditionEvaluator(platformName = "test")
        // Should not throw, even if env vars don't exist
        assertFalse(evaluator.evaluate("\${NONEXISTENT_VAR} == 'value'"))
    }

    @Test
    fun `evaluate case insensitive truthy values`() {
        val evaluator = ConditionEvaluator(
            envProvider = {
                when (it) {
                    "FALSE_UPPER" -> "FALSE"
                    "FALSE_MIXED" -> "False"
                    "NO_UPPER" -> "NO"
                    "OFF_UPPER" -> "OFF"
                    else -> null
                }
            }
        )
        assertFalse(evaluator.evaluate("\${FALSE_UPPER}"))
        assertFalse(evaluator.evaluate("\${FALSE_MIXED}"))
        assertFalse(evaluator.evaluate("\${NO_UPPER}"))
        assertFalse(evaluator.evaluate("\${OFF_UPPER}"))
    }

    @Test
    fun `an extends condition runs on the core conditions engine and its nesting limit`() {
        val evaluator = ConditionEvaluator(envProvider = { if (it == "CI") "true" else null })
        val depth = KarandashLimits.MAX_NESTING_DEPTH + 1

        assertTrue(evaluator.evaluate("(".repeat(depth - 1) + "\${CI}" + ")".repeat(depth - 1)))
        val tooDeep = assertFailsWith<ConfigException> {
            evaluator.evaluate("(".repeat(depth) + "\${CI}" + ")".repeat(depth))
        }
        assertTrue(tooDeep.message?.contains("E0016") == true, tooDeep.message)
    }

    @Test
    fun `an extends condition with tokens left over is malformed`() {
        val evaluator = ConditionEvaluator(envProvider = { if (it == "CI") "true" else null })

        listOf("\${CI} 'extra'", "\${CI} == 'true' == 'true'", "\${CI})").forEach { condition ->
            val error = assertFailsWith<ConfigException>(condition) { evaluator.evaluate(condition) }
            assertTrue(error.message?.contains("E0016") == true, error.message)
        }
    }
}
