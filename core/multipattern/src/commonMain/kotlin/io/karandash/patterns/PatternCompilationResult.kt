package io.karandash.patterns

import kotlinx.serialization.Serializable

/**
 * Result of compiling a pattern string into a [Regex].
 *
 * All 5 pattern tiers resolve to this sealed type - callers handle
 * success/error uniformly regardless of how the pattern was expressed.
 */
sealed class PatternCompilationResult {
    /**
     * The pattern was compiled successfully.
     *
     * @param regex The compiled, ReDoS-safe regular expression.
     * @param originalPattern The raw pattern string before prefix stripping.
     * @param type Which tier the pattern was resolved through.
     *
     * Not @Serializable: runtime context holder, carries a compiled [Regex].
     */
    data class Success(
        val regex: Regex,
        val originalPattern: String,
        val type: PatternType,
    ) : PatternCompilationResult()

    /**
     * The pattern could not be compiled.
     *
     * @param message Human-readable description of the failure.
     * @param originalPattern The raw pattern string that caused the error.
     */
    @Serializable
    data class Error(
        val message: String,
        val originalPattern: String,
    ) : PatternCompilationResult()
}
