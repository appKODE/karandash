package io.karandash.patterns

import io.karandash.helpers.SafeRegexCompiler
import io.karandash.utils.GlobTranslator

/**
 * Compiles text glob expressions to [PatternCompilationResult].
 *
 * Supported glob syntax:
 * - `*`       - matches any sequence of characters (excluding nothing)
 * - `?`       - matches exactly one character
 * - `{a,b,c}` - alternation (matches any of the comma-separated branches; branches may hold wildcards and nested braces)
 * - `[abc]`   - character class; `[!abc]` negates it
 * - All other characters are treated as literals and escaped.
 *
 * The resulting regex is auto-anchored (`^...$`).
 *
 * @param regexCompiler Compiles the expanded regex; pass the run's managed compiler so globs share its cache.
 */
class TextGlobCompiler(
    private val regexCompiler: SafeRegexCompiler = SafeRegexCompiler.Default,
) {
    /**
     * Compiles [glob] to a [PatternCompilationResult].
     *
     * @param glob Glob expression without the `glob:` prefix.
     */
    fun compile(glob: String): PatternCompilationResult {
        val regex = globToRegex(glob)
        return when (val result = regexCompiler.compile(regex)) {
            is SafeRegexCompiler.CompileResult.Success -> {
                PatternCompilationResult.Success(
                    regex = result.regex,
                    originalPattern = glob,
                    type = PatternType.GLOB,
                )
            }

            is SafeRegexCompiler.CompileResult.TooLong -> {
                PatternCompilationResult.Error(
                    message = "Glob pattern too long after expansion (${result.length} > ${result.maxLength} chars)",
                    originalPattern = glob,
                )
            }

            is SafeRegexCompiler.CompileResult.NestedQuantifiers -> {
                PatternCompilationResult.Error(
                    message = "Glob expansion produced nested quantifiers (depth ${result.depth})",
                    originalPattern = glob,
                )
            }

            is SafeRegexCompiler.CompileResult.InvalidPattern -> {
                PatternCompilationResult.Error(
                    message = "Glob expansion produced invalid regex: ${result.message}",
                    originalPattern = glob,
                )
            }
        }
    }

    /**
     * Returns `true` if [pattern] is a negation (exclusion) glob starting with `!`.
     *
     * Callers can use this to separate include/exclude patterns before compilation:
     * compile includes first, then compile excludes (with [stripNegation]) and
     * filter matches that hit any exclude pattern.
     */
    fun isNegation(pattern: String): Boolean = pattern.startsWith("!")

    /**
     * Strips the leading `!` negation prefix from [pattern].
     *
     * Returns the original string unchanged if it does not start with `!`.
     */
    fun stripNegation(pattern: String): String = pattern.removePrefix("!")

    private fun globToRegex(glob: String): String = GlobTranslator.toRegexSource(glob, GlobTranslator.Mode.TEXT)
}
