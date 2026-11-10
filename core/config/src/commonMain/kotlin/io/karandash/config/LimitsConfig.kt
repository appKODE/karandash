package io.karandash.config

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * User-facing limits configuration parsed from the `limits:` config block.
 *
 * Controls safety bounds for expressions, extends chains, glob expansion,
 * regex compilation, and pattern expansion. All fields have sensible defaults
 * that match the historical hardcoded values from [KarandashLimits].
 *
 * Limits are DoS guards and the config may come from the MR branch, so every field has hard bounds:
 * consumers read only the `effective*` value, and [boundsWarnings] names each clamped field.
 * `extends`, `fetchTimeout` and `glob` bound config resolution itself (`glob` bounds glob `extends:` entries);
 * they inherit like any other key: each layer's parents are fetched under the value merged from the root down to
 * that layer (the closer layer wins).
 *
 * ```yaml
 * limits:
 *   max-findings: 10000
 *   fetch-timeout: "10s"
 *   expressions:
 *     max-length: 1000
 *     max-nesting-depth: 50
 *   extends:
 *     max-entries: 10
 *     max-depth: 5
 *     max-chain-size: 20
 *   glob:
 *     max-expansion: 10
 *     max-directory-depth: 5
 *   regex:
 *     max-pattern-length: 500
 *     max-quantifier-nesting: 1
 *   patterns:
 *     max-expansion-depth: 20
 * ```
 */
@Serializable
data class LimitsConfig(
    /** Maximum number of findings before truncation. */
    val maxFindings: Int = DEFAULT_MAX_FINDINGS,
    /** Remote config fetch timeout. */
    val fetchTimeout: KarandashDuration = DEFAULT_FETCH_TIMEOUT,
    /** Expression evaluation limits. */
    val expressions: ExpressionsLimitsConfig = ExpressionsLimitsConfig(),
    /** Config extends chain limits. */
    val extends: ExtendsLimitsConfig = ExtendsLimitsConfig(),
    /** Glob expansion limits. */
    val glob: GlobLimitsConfig = GlobLimitsConfig(),
    /** Regex compilation safety limits. */
    val regex: RegexLimitsConfig = RegexLimitsConfig(),
    /** Named pattern expansion limits. */
    val patterns: PatternLimitsConfig = PatternLimitsConfig(),
) {
    /** Effective findings cap. Coerced to `[100, 100000]`. */
    val effectiveMaxFindings: Int get() = maxFindings.coerceIn(MAX_FINDINGS_BOUNDS)

    /** Effective remote fetch timeout. Coerced to `[1s, 120s]`. */
    val effectiveFetchTimeout: KarandashDuration get() = fetchTimeout.coerceIn(MIN_FETCH_TIMEOUT, MAX_FETCH_TIMEOUT)

    /** One warning per field whose configured value is outside its bounds and was clamped. */
    fun boundsWarnings(): List<String> =
        listOfNotNull(
            clampWarning("limits.maxFindings", maxFindings, effectiveMaxFindings),
            clampWarning("limits.fetchTimeout", fetchTimeout, effectiveFetchTimeout),
            clampWarning("limits.expressions.maxLength", expressions.maxLength, expressions.effectiveMaxLength),
            clampWarning("limits.expressions.maxNestingDepth", expressions.maxNestingDepth, expressions.effectiveMaxNestingDepth),
            clampWarning("limits.extends.maxEntries", extends.maxEntries, extends.effectiveMaxEntries),
            clampWarning("limits.extends.maxDepth", extends.maxDepth, extends.effectiveMaxDepth),
            clampWarning("limits.extends.maxChainSize", extends.maxChainSize, extends.effectiveMaxChainSize),
            clampWarning("limits.glob.maxExpansion", glob.maxExpansion, glob.effectiveMaxExpansion),
            clampWarning("limits.glob.maxDirectoryDepth", glob.maxDirectoryDepth, glob.effectiveMaxDirectoryDepth),
            clampWarning("limits.regex.maxPatternLength", regex.maxPatternLength, regex.effectiveMaxPatternLength),
            clampWarning("limits.regex.maxQuantifierNesting", regex.maxQuantifierNesting, regex.effectiveMaxQuantifierNesting),
            clampWarning("limits.patterns.maxExpansionDepth", patterns.maxExpansionDepth, patterns.effectiveMaxExpansionDepth),
        )

    companion object {
        const val DEFAULT_MAX_FINDINGS = 10_000
        val DEFAULT_FETCH_TIMEOUT: KarandashDuration = KarandashDuration.ofSeconds(10)
        private val MAX_FINDINGS_BOUNDS = 100..100_000
        private val MIN_FETCH_TIMEOUT = KarandashDuration.ofSeconds(1)
        private val MAX_FETCH_TIMEOUT = KarandashDuration.ofSeconds(120)
    }
}

/** `null` when [configured] is within bounds, else a warning that it was clamped to [effective]. */
internal fun clampWarning(
    key: String,
    configured: Any,
    effective: Any,
): String? = if (configured == effective) null else "Config key '$key' value $configured is out of bounds; using $effective"

/**
 * Limits for condition expression evaluation.
 */
@Serializable
data class ExpressionsLimitsConfig(
    /** Maximum length of a condition expression string. */
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    /** Maximum nesting depth of condition expression AST. */
    val maxNestingDepth: Int = DEFAULT_MAX_NESTING_DEPTH,
) {
    /** Coerced to `[100, 10000]`. */
    val effectiveMaxLength: Int get() = maxLength.coerceIn(MAX_LENGTH_BOUNDS)

    /** Coerced to `[5, 200]`. */
    val effectiveMaxNestingDepth: Int get() = maxNestingDepth.coerceIn(NESTING_BOUNDS)

    companion object {
        const val DEFAULT_MAX_LENGTH = 1000
        const val DEFAULT_MAX_NESTING_DEPTH = 50
        private val MAX_LENGTH_BOUNDS = 100..10_000
        private val NESTING_BOUNDS = 5..200
    }
}

/**
 * Limits for config `extends:` chain resolution.
 */
@Serializable
data class ExtendsLimitsConfig(
    /** Maximum number of entries in the extends chain. */
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** Maximum inheritance depth for config extends. */
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    /** Maximum total chain size for config extends. */
    val maxChainSize: Int = DEFAULT_MAX_CHAIN_SIZE,
) {
    /** Coerced to `[1, 50]`. */
    val effectiveMaxEntries: Int get() = maxEntries.coerceIn(ENTRIES_BOUNDS)

    /** Coerced to `[1, 20]`. */
    val effectiveMaxDepth: Int get() = maxDepth.coerceIn(DEPTH_BOUNDS)

    /** Coerced to `[2, 100]`. */
    val effectiveMaxChainSize: Int get() = maxChainSize.coerceIn(CHAIN_SIZE_BOUNDS)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 10
        const val DEFAULT_MAX_DEPTH = 5
        const val DEFAULT_MAX_CHAIN_SIZE = 20
        private val ENTRIES_BOUNDS = 1..50
        private val DEPTH_BOUNDS = 1..20
        private val CHAIN_SIZE_BOUNDS = 2..100
    }
}

/**
 * Limits for glob pattern expansion.
 */
@Serializable
data class GlobLimitsConfig(
    /** Maximum number of files a glob pattern can expand to. */
    val maxExpansion: Int = DEFAULT_MAX_EXPANSION,
    /** Maximum directory depth for recursive glob expansion. */
    val maxDirectoryDepth: Int = DEFAULT_MAX_DIRECTORY_DEPTH,
) {
    /** Coerced to `[1, 100]`. */
    val effectiveMaxExpansion: Int get() = maxExpansion.coerceIn(EXPANSION_BOUNDS)

    /** Coerced to `[1, 20]`. */
    val effectiveMaxDirectoryDepth: Int get() = maxDirectoryDepth.coerceIn(DIRECTORY_DEPTH_BOUNDS)

    companion object {
        const val DEFAULT_MAX_EXPANSION = 10
        const val DEFAULT_MAX_DIRECTORY_DEPTH = 5
        private val EXPANSION_BOUNDS = 1..100
        private val DIRECTORY_DEPTH_BOUNDS = 1..20
    }
}

/**
 * ReDoS protection limits for regex compilation.
 *
 * Values are coerced to safe bounds to prevent users from disabling protections entirely.
 */
@Serializable
data class RegexLimitsConfig(
    /** Maximum allowed regex pattern length. Coerced to `[50, 2000]`. */
    val maxPatternLength: Int = DEFAULT_MAX_PATTERN_LENGTH,
    /** Maximum allowed quantifier nesting depth. Coerced to `[1, 3]`. */
    val maxQuantifierNesting: Int = DEFAULT_MAX_QUANTIFIER_NESTING,
) {
    /** Effective pattern length after applying safe bounds. */
    val effectiveMaxPatternLength: Int get() = maxPatternLength.coerceIn(MIN_PATTERN_LENGTH, MAX_PATTERN_LENGTH_BOUND)

    /** Effective quantifier nesting after applying safe bounds. */
    val effectiveMaxQuantifierNesting: Int get() = maxQuantifierNesting.coerceIn(MIN_QUANTIFIER_NESTING, MAX_QUANTIFIER_NESTING_BOUND)

    companion object {
        const val DEFAULT_MAX_PATTERN_LENGTH = 500
        const val DEFAULT_MAX_QUANTIFIER_NESTING = 1
        private const val MIN_PATTERN_LENGTH = 50
        private const val MAX_PATTERN_LENGTH_BOUND = 2000
        private const val MIN_QUANTIFIER_NESTING = 1
        private const val MAX_QUANTIFIER_NESTING_BOUND = 3
    }
}

/**
 * Limits for named pattern expansion.
 *
 * Values are coerced to safe bounds to prevent unbounded recursion.
 */
@Serializable
data class PatternLimitsConfig(
    /** Maximum recursion depth for named pattern expansion. Coerced to `[5, 50]`. */
    val maxExpansionDepth: Int = DEFAULT_MAX_EXPANSION_DEPTH,
) {
    /** Effective depth after applying safe bounds. */
    val effectiveMaxExpansionDepth: Int get() = maxExpansionDepth.coerceIn(MIN_DEPTH, MAX_DEPTH_BOUND)

    companion object {
        const val DEFAULT_MAX_EXPANSION_DEPTH = 20
        private const val MIN_DEPTH = 5
        private const val MAX_DEPTH_BOUND = 50
    }
}
