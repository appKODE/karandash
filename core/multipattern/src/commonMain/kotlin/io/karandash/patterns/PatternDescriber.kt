package io.karandash.patterns

import io.karandash.helpers.PatternPrefixes
import io.karandash.helpers.RegexHelper
import kotlinx.serialization.Serializable
import ru.kode.pomskykt.Expr
import ru.kode.pomskykt.analysis.TestGenerator
import ru.kode.pomskykt.analysis.TestGeneratorOptions
import ru.kode.pomskykt.decompiler.Decompiler
import ru.kode.pomskykt.options.CompileOptions
import ru.kode.pomskykt.options.RegexFlavor

/**
 * Matching and non-matching sample strings generated from a pattern.
 *
 * @param matching Strings that should match the compiled pattern.
 * @param nonMatching Strings that should not match the compiled pattern.
 */
@Serializable
data class PatternExamples(
    val matching: List<String>,
    val nonMatching: List<String>,
)

/**
 * Converts raw pattern strings into human-readable descriptions
 * using the Pomsky decompiler.
 *
 * For bare regex or `regex:` prefixed patterns, decompiles the regex
 * to Pomsky DSL which is more readable. For `pomsky:`, `glob:`, and
 * `@named` patterns, returns the original since they're already readable.
 */
object PatternDescriber {
    private const val DEFAULT_MAX_EXAMPLES = 5
    private const val PREFIX_POMSKY = PatternPrefixes.POMSKY
    private const val PREFIX_GLOB = PatternPrefixes.GLOB
    private const val PREFIX_REGEX = PatternPrefixes.REGEX
    private const val PREFIX_NAMED = "@"

    /**
     * Returns a human-readable version of the given pattern string.
     *
     * - `pomsky:` patterns → returned as-is (already readable)
     * - `glob:` patterns → returned as-is (already readable)
     * - `@named` patterns → returned as-is (already readable)
     * - `regex:` or bare regex → decompiled to Pomsky DSL
     *
     * Falls back to the original pattern if decompilation fails.
     */
    fun describe(pattern: String): String =
        when {
            pattern.startsWith(PREFIX_POMSKY) -> pattern.removePrefix(PREFIX_POMSKY).trim()
            pattern.startsWith(PREFIX_GLOB) -> pattern
            pattern.startsWith(PREFIX_NAMED) -> resolveNamedToRegex(pattern)?.let { decompileOrFallback(it) } ?: pattern
            pattern.startsWith(PREFIX_REGEX) -> decompileOrFallback(pattern.removePrefix(PREFIX_REGEX))
            else -> decompileOrFallback(pattern)
        }

    /**
     * Returns a plain-English explanation of the given pattern.
     *
     * - `pomsky:` patterns → compiled to regex first, then explained
     * - `glob:` / `@named` → returns null (not regex-based)
     * - `regex:` or bare regex → explained directly
     *
     * @return English explanation, or null if the pattern cannot be explained.
     */
    fun explain(pattern: String): String? =
        when {
            pattern.startsWith(PREFIX_POMSKY) -> explainPomsky(pattern.removePrefix(PREFIX_POMSKY).trim())
            pattern.startsWith(PREFIX_GLOB) -> null
            pattern.startsWith(PREFIX_NAMED) -> resolveNamedToRegex(pattern)?.let { explainRegex(it) }
            pattern.startsWith(PREFIX_REGEX) -> explainRegex(pattern.removePrefix(PREFIX_REGEX))
            else -> explainRegex(pattern)
        }

    /**
     * Generates example matching and non-matching strings for the given pattern.
     *
     * - `pomsky:` → compiled to regex, then generates examples from the IR
     * - `regex:` / bare regex → parsed to IR, then generates examples
     * - `glob:` / `@named` → returns null
     *
     * @param pattern The pattern string.
     * @param maxExamples Maximum number of examples per category.
     * @param sampleTexts Custom text samples for generic positions (`.`, `.*`, `.+`).
     *   Pass actual MR data (e.g., title, commit message) for context-aware examples.
     * @return [PatternExamples], or null if examples cannot be generated.
     */
    fun generateExamples(
        pattern: String,
        maxExamples: Int = DEFAULT_MAX_EXAMPLES,
        sampleTexts: List<String> = emptyList(),
    ): PatternExamples? =
        when {
            pattern.startsWith(PREFIX_POMSKY) -> generateFromPomsky(
                pattern.removePrefix(PREFIX_POMSKY).trim(),
                maxExamples,
                sampleTexts,
            )

            pattern.startsWith(PREFIX_GLOB) -> null

            pattern.startsWith(PREFIX_NAMED) -> resolveNamedToRegex(pattern)?.let {
                generateFromRegex(it, maxExamples, sampleTexts)
            }

            pattern.startsWith(PREFIX_REGEX) -> generateFromRegex(
                pattern.removePrefix(PREFIX_REGEX),
                maxExamples,
                sampleTexts,
            )

            else -> generateFromRegex(pattern, maxExamples, sampleTexts)
        }

    private fun decompileOrFallback(regex: String): String {
        if (regex.isBlank()) return regex
        return try {
            val result = Decompiler.decompile(regex, RegexFlavor.Java)
            result.pomsky ?: regex
        } catch (_: IllegalArgumentException) {
            regex
        } catch (_: IllegalStateException) {
            regex
        }
    }

    private fun explainRegex(regex: String): String? {
        if (regex.isBlank()) return null
        return try {
            val result = Decompiler.explain(regex, RegexFlavor.Java)
            result.explanation
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun explainPomsky(expression: String): String? {
        if (expression.isBlank()) return null
        val (regex, _, _) = Expr.parseAndCompile(
            expression,
            CompileOptions(flavor = RegexFlavor.Java),
        )
        if (regex == null) return null
        return explainRegex(regex)
    }

    private fun generateFromRegex(
        regex: String,
        maxExamples: Int,
        sampleTexts: List<String>,
    ): PatternExamples? {
        if (regex.isBlank()) return null
        return try {
            val ir = Decompiler.parse(regex, RegexFlavor.Java)
            val options = TestGeneratorOptions(
                maxExamples = maxExamples,
                sampleTexts = sampleTexts,
            )
            val tests = TestGenerator.generate(ir, options)
            // An empty string is a valid match for `x*` or `a?` but shows as nothing in a hint.
            PatternExamples(
                matching = tests.matching.filter { it.isNotEmpty() },
                nonMatching = tests.nonMatching.filter { it.isNotEmpty() },
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    /**
     * Resolves a `@named` pattern to its compiled regex string via [RegexHelper],
     * or returns `null` if the pattern compiler is unavailable or compilation fails.
     */
    private fun resolveNamedToRegex(pattern: String): String? = RegexHelper.resolveToRegex(pattern)?.pattern

    private fun generateFromPomsky(
        expression: String,
        maxExamples: Int,
        sampleTexts: List<String>,
    ): PatternExamples? {
        if (expression.isBlank()) return null
        val (regex, _, _) = Expr.parseAndCompile(
            expression,
            CompileOptions(flavor = RegexFlavor.Java),
        )
        if (regex == null) return null
        return generateFromRegex(regex, maxExamples, sampleTexts)
    }
}
