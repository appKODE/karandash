package io.karandash.config

/**
 * Returns the effective regex max pattern length, respecting the coerced bounds.
 */
fun LimitsConfig.effectiveRegexMaxPatternLength(): Int = regex.effectiveMaxPatternLength

/**
 * Returns the effective regex max quantifier nesting, respecting the coerced bounds.
 */
fun LimitsConfig.effectiveRegexMaxQuantifierNesting(): Int = regex.effectiveMaxQuantifierNesting

/**
 * Returns the effective pattern expansion max depth, respecting the coerced bounds.
 */
fun LimitsConfig.effectivePatternMaxExpansionDepth(): Int = patterns.effectiveMaxExpansionDepth
