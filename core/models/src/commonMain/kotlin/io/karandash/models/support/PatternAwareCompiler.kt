package io.karandash.models.support

import io.karandash.models.finding.Finding

/**
 * Compiles a pattern string (named, glob, pomsky, regex, or bare) to a [Regex].
 *
 * Injected into [io.karandash.helpers.RegexHelper] at startup so that all
 * existing validators gain pattern-system support without code changes.
 * When absent, only bare regex compilation is available.
 */
fun interface PatternAwareCompiler {
    /**
     * Resolves and compiles [pattern] to a [Regex].
     *
     * On failure, appends an ERROR [Finding] to [findings] and returns `null`.
     *
     * @param pattern The raw pattern string (may start with `@`, `glob:`, `pomsky:`, or `regex:`).
     * @param ruleId The rule ID to attribute any error findings to.
     * @param findings Mutable list to append error findings to on compilation failure.
     * @return Compiled regex on success, or `null` on failure.
     */
    fun compile(
        pattern: String,
        ruleId: String,
        findings: MutableList<Finding>
    ): Regex?
}
