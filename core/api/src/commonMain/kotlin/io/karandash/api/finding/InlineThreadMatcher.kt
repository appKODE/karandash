package io.karandash.api.finding

import io.karandash.models.finding.Finding

/**
 * Outcome of [InlineThreadMatcher.match].
 *
 * Not @Serializable: [F] and [T] are the caller's item and thread handles (a finding, a comment, a discussion id).
 *
 * @property matched Each current item that already has a thread, with that thread.
 * @property unmatchedFindings Current items with no thread yet (to post).
 * @property unmatchedThreads Threads no current item matched (candidates to resolve).
 */
public data class InlineThreadMatch<F, T>(
    val matched: Map<F, T>,
    val unmatchedFindings: List<F>,
    val unmatchedThreads: List<T>,
)

/**
 * Matches current findings to existing inline threads by the [InlineFindingRef] markers in each thread.
 * Pure: no platform calls. An item takes the first thread that matches, trying in order the anchor
 * fingerprint ([InlineFindingRef.fpv], when both sides have one), then the semantic fingerprint
 * ([InlineFindingRef.semanticFingerprint]), then rule, path and the normalized message prefix. The last tier applies only
 * when one side has no fpv, and each ref matched that way absorbs one item. One thread may match several items.
 */
public object InlineThreadMatcher {
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Matches [findings] against [threads] (thread handle to the markers parsed from its notes). */
    public fun <T> match(
        findings: List<Finding>,
        threads: Map<T, List<InlineFindingRef>>,
    ): InlineThreadMatch<Finding, T> = match(findings, { listOf(InlineFindingMarker.refOf(it)) }, threads)

    /** Matches [items], each identified by the markers [refsOf] gives (e.g. parsed from a comment body), against [threads]. */
    public fun <F, T> match(
        items: List<F>,
        refsOf: (F) -> List<InlineFindingRef>,
        threads: Map<T, List<InlineFindingRef>>,
    ): InlineThreadMatch<F, T> {
        val consumed = mutableSetOf<Pair<T, Int>>()
        val matched = items
            .mapNotNull { item -> threadFor(refsOf(item), threads, consumed)?.let { item to it } }
            .toMap()
        val usedThreads = matched.values.toSet()
        return InlineThreadMatch(
            matched = matched,
            unmatchedFindings = items.filterNot { it in matched },
            unmatchedThreads = threads.keys.filterNot { it in usedThreads },
        )
    }

    private fun <T> threadFor(
        refs: List<InlineFindingRef>,
        threads: Map<T, List<InlineFindingRef>>,
        consumed: MutableSet<Pair<T, Int>>,
    ): T? {
        fun find(
            once: Boolean = false,
            same: (InlineFindingRef, InlineFindingRef) -> Boolean,
        ): Pair<T, Int>? =
            threads.entries.firstNotNullOfOrNull { (thread, posted) ->
                posted.indices
                    .firstOrNull { index -> !(once && thread to index in consumed) && refs.any { same(it, posted[index]) } }
                    ?.let { thread to it }
            }
        // Rule, path and message prefix only stand in for a missing fpv, and each such ref absorbs one item: two
        // current findings with the same message on different lines are two findings, not one
        val hit = find { a, b -> a.fpv.isNotEmpty() && a.fpv == b.fpv }
            ?: find { a, b -> a.semanticFingerprint.isNotEmpty() && a.semanticFingerprint == b.semanticFingerprint }
            ?: find(once = true) { a, b ->
                (a.fpv.isEmpty() || b.fpv.isEmpty()) &&
                    a.rule == b.rule &&
                    a.path == b.path &&
                    normalize(a.messagePrefix) == normalize(b.messagePrefix)
            }
            ?: return null
        consumed += hit
        return hit.first
    }

    private fun normalize(text: String): String = text.replace(WHITESPACE_REGEX, " ").trim().lowercase()
}
