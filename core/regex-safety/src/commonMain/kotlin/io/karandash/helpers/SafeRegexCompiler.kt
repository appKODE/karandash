package io.karandash.helpers

import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.RegexCacheConfig
import io.karandash.cache.TieredCache
import kotlinx.atomicfu.atomic

/**
 * ReDoS-safe regex compilation engine.
 *
 * Rejects patterns that are too long or contain nested quantifiers (e.g., `(a+)+`)
 * that cause catastrophic backtracking. Pure compilation - no [io.karandash.models.finding.Finding]
 * dependency, so it can be used in any context.
 *
 * @param cache Cache for compiled [Regex] instances.
 */
class SafeRegexCompiler(
    private val cache: TieredCache<String, Regex>,
) {
    /**
     * Result of a safe regex compilation attempt.
     */
    sealed class CompileResult {
        /** Not @Serializable: in-process compile outcome; [Success] carries a compiled [Regex]. */
        data class Success(
            val regex: Regex,
        ) : CompileResult()

        /** Not @Serializable: in-process compile outcome; [Success] carries a compiled [Regex]. */
        data class TooLong(
            val length: Int,
            val maxLength: Int,
        ) : CompileResult()

        /** Not @Serializable: in-process compile outcome; [Success] carries a compiled [Regex]. */
        data class NestedQuantifiers(
            val depth: Int,
        ) : CompileResult()

        /** Not @Serializable: in-process compile outcome; [Success] carries a compiled [Regex]. */
        data class InvalidPattern(
            val message: String,
        ) : CompileResult()
    }

    /**
     * Attempts to compile [pattern] with ReDoS safety guards.
     *
     * @return [CompileResult.Success] with the compiled regex, or an error variant.
     */
    fun compile(pattern: String): CompileResult {
        val limits = installedLimits.value ?: DEFAULT_LIMITS
        if (pattern.length > limits.maxPatternLength) {
            return CompileResult.TooLong(pattern.length, limits.maxPatternLength)
        }
        val nestingDepth = quantifierNestingDepth(pattern)
        if (nestingDepth > limits.maxQuantifierNesting) {
            return CompileResult.NestedQuantifiers(nestingDepth)
        }
        return try {
            val regex = cache.getOrCompute(pattern) { Regex(pattern) }
            CompileResult.Success(regex)
        } catch (e: IllegalArgumentException) {
            CompileResult.InvalidPattern(e.message ?: "Invalid regex")
        }
    }

    /**
     * Attempts to compile [pattern], returning `null` on any failure.
     *
     * Convenience method for init blocks where error details are not needed.
     */
    fun compileOrNull(pattern: String): Regex? =
        when (val result = compile(pattern)) {
            is CompileResult.Success -> result.regex
            else -> null
        }

    /**
     * `true` when [pattern] repeats a group whose mandatory literals its inner unbounded atom can all match
     * (`(?:, \[KEY-\d+\] .+)*`). Such patterns compile but can backtrack polynomially; callers warn.
     */
    fun hasAmbiguousRepetition(pattern: String): Boolean = RegexNestingAnalyzer.analyze(pattern).ambiguous

    /**
     * Computes the maximum quantifier nesting depth in [pattern] (see [RegexNestingAnalyzer]).
     *
     * A quantified sub-group inside a quantified group (`((a)+)+`) is nested. An unbounded atom (`a+`, `.*`,
     * `\w{2,}`) inside a group repeated with `+`, `*` or `{n,}` is nested too, unless the group has a mandatory
     * literal the atom cannot match: `(\[MAPS-\d+\])+` is safe, `(a+)+`, `(\w+\s?)+` and `(.*)+` are not.
     *
     * @return Maximum nesting level (0 = no nesting, 1 = flat, 2+ = nested/dangerous).
     */
    internal fun quantifierNestingDepth(pattern: String): Int = RegexNestingAnalyzer.analyze(pattern).depth

    /**
     * Run-wide `limits.regex` values (already clamped), installed with [installLimits] for every
     * compiler instance, including [Default].
     */
    class Limits(
        val maxPatternLength: Int,
        val maxQuantifierNesting: Int,
    )

    companion object {
        private val installedLimits = atomic<Limits?>(null)

        /** Applies [limits] to every compiler until [clearLimits]; later installs win. */
        fun installLimits(limits: Limits) {
            installedLimits.value = limits
        }

        /** Restores the built-in limits only if [limits] is still installed (compare-and-set). */
        fun clearLimits(limits: Limits) {
            installedLimits.compareAndSet(limits, null)
        }

        /** Default maximum allowed pattern length to prevent excessive compilation time. */
        const val MAX_PATTERN_LENGTH = 500

        /** Cache name for use with [io.karandash.cache.CacheManager]. */
        const val CACHE_NAME = RegexCacheConfig.CACHE_NAME

        /**
         * Maximum allowed quantifier nesting depth.
         * Patterns like `(a+)+` (depth 2) cause catastrophic backtracking.
         */
        private const val MAX_QUANTIFIER_NESTING = 1

        private val DEFAULT_LIMITS = Limits(MAX_PATTERN_LENGTH, MAX_QUANTIFIER_NESTING)

        private const val MAX_COMPILED_CACHE_SIZE = 1000

        /** Default instance for non-pipeline usage (standalone tools, tests). */
        val Default: SafeRegexCompiler = SafeRegexCompiler(
            cache = TieredCache(
                name = CACHE_NAME,
                maxEntries = MAX_COMPILED_CACHE_SIZE,
                tier = CacheTier.MEMORY_ONLY,
            ),
        )

        /**
         * Creates a [SafeRegexCompiler] backed by a [CacheManager]-managed cache.
         *
         * Use this in the validation pipeline so the regex cache participates in
         * central stats, invalidation, and lifecycle management.
         */
        fun createManaged(cacheManager: CacheManager): SafeRegexCompiler {
            val cache = cacheManager.getOrCreate<String, Regex>(
                CACHE_NAME,
                CacheSpec(maxEntries = MAX_COMPILED_CACHE_SIZE),
            )
            return SafeRegexCompiler(cache)
        }
    }
}
