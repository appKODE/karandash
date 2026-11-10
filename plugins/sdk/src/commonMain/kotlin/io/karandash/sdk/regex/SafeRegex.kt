package io.karandash.sdk.regex

import io.karandash.helpers.RegexHelper
import io.karandash.models.finding.Finding

/**
 * Compiles user-supplied regex patterns with the host's ReDoS guards (pattern length and quantifier nesting).
 * Plugins use this instead of `Regex(userPattern)` for any pattern that comes from config.
 */
public object SafeRegex {
    /** [pattern] compiled, or `null` when it is invalid or unsafe. */
    public fun compileOrNull(pattern: String): Regex? = RegexHelper.compileOrNull(pattern)

    /**
     * [pattern] compiled, or `null` with an ERROR finding for [rule] appended to [findings] explaining why.
     * Pattern syntax (`@named`, `glob:`, `pomsky:`, `regex:`) resolves against the host's patterns when available.
     */
    public fun tryCompile(
        pattern: String,
        rule: String,
        findings: MutableList<Finding>,
    ): Regex? = RegexHelper.tryCompile(pattern, rule, findings)
}
