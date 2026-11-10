package io.karandash.utils

/**
 * Resolves `rule-enrichment:`/[io.karandash.api.module.RuleModule.supportedEnrichment]-style
 * patterns (bare prefixes like `"jira"`, or explicit globs like `"gitlab:*"`) against real
 * enrichment IDs, via [GlobMatcher]. A pattern with no `*` is treated as a prefix (backward
 * compatible with the historical `startsWith` convention) by implicitly appending `*`.
 */
object EnrichmentGlobMatcher {
    /** Returns the subset of [availableIds] matched by any of [patterns]. */
    fun resolve(
        patterns: List<String>,
        availableIds: Set<String>,
    ): Set<String> {
        if (patterns.isEmpty() || availableIds.isEmpty()) return emptySet()
        val normalized = patterns.map(::normalize)
        return availableIds.filterTo(mutableSetOf()) { id -> normalized.any { GlobMatcher.matches(id, it) } }
    }

    /**
     * The enrichment patterns a rule module sees: its [supported] patterns plus the `rule-enrichment:` [bindings] of
     * each of its [ruleIds].
     */
    fun patternsFor(
        supported: List<String>,
        bindings: Map<String, List<String>>,
        ruleIds: Collection<String>,
    ): List<String> = (supported + ruleIds.flatMap { bindings[it].orEmpty() }).distinct()

    /** True if [pattern] matches at least one entry of [availableIds]. */
    fun matchesAny(
        pattern: String,
        availableIds: Set<String>,
    ): Boolean = availableIds.any { id -> GlobMatcher.matches(id, normalize(pattern)) }

    private fun normalize(pattern: String): String = if ('*' in pattern) pattern else "$pattern*"
}
