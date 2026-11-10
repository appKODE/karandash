package io.karandash.patterns

import io.karandash.helpers.PatternPrefixes

/**
 * Identifies which tier of the 5-tier pattern system a given pattern string belongs to.
 *
 * Detection is prefix-based and evaluated in the order of enum declaration.
 */
enum class PatternType {
    /** Named pattern reference - starts with `@`, e.g. `@jira-ticket`. */
    NAMED,

    /** Pomsky DSL expression - starts with `pomsky:`. */
    POMSKY,

    /** Text glob expression - starts with `glob:`. */
    GLOB,

    /** Explicit regex - starts with `regex:`. */
    REGEX,

    /** No prefix - treated as raw regex for backward compatibility. */
    BARE,
}

/**
 * Detects the [PatternType] of [pattern] by inspecting its prefix.
 *
 * Evaluation is ordered: `@` → `pomsky:` → `glob:` → `regex:` → bare.
 */
fun detectPatternType(pattern: String): PatternType =
    when (PatternPrefixes.of(pattern)) {
        PatternPrefixes.NAMED -> PatternType.NAMED
        PatternPrefixes.POMSKY -> PatternType.POMSKY
        PatternPrefixes.GLOB -> PatternType.GLOB
        PatternPrefixes.REGEX -> PatternType.REGEX
        else -> PatternType.BARE
    }
