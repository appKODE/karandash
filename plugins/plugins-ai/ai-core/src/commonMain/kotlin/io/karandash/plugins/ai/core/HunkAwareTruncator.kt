package io.karandash.plugins.ai.core

import io.karandash.utils.mr.HunkParser
import kotlinx.serialization.Serializable

/**
 * Budget-aware diff truncator that preserves hunk boundaries.
 *
 * Unlike the historical `DiffSectionBuilder.build` path - which cuts
 * at a char boundary then back-trims to the last newline, potentially chopping a
 * hunk mid-line - this truncator always includes complete hunks or none of a
 * given hunk at all.
 *
 * Hunks are scored by added-line density plus a small position bonus (earlier
 * hunks win ties). Hunks are greedily selected in score order until the budget
 * would be exceeded, then the kept hunks are reordered to their original file
 * position so the output reads top-to-bottom.
 *
 * Edge cases handled explicitly:
 * - Binary files (`Binary files a/... and b/... differ`) → `(binary file)`.
 * - Submodule updates (`Subproject commit ...`) → `(submodule change)`.
 * - Input with no `@@` header → treated as a single atomic block, still clamped.
 * - Null / blank input → empty result.
 * - `budget <= 0` → empty result.
 */
object HunkAwareTruncator {
    /** Marker appended when one or more hunks are dropped to fit the budget. */
    private const val OMITTED_SUFFIX_SINGLE = "... (1 hunk omitted — budget limit)"
    private const val OMITTED_SUFFIX_PLURAL_PREFIX = "... ("
    private const val OMITTED_SUFFIX_PLURAL_SUFFIX = " hunks omitted — budget limit)"

    /** Result bundle - carries per-file counts for [io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics]. */
    @Serializable
    data class TruncationResult(
        val output: String,
        val keptHunks: Int,
        val omittedHunks: Int,
        val totalChars: Int,
    ) {
        companion object {
            val EMPTY: TruncationResult = TruncationResult("", 0, 0, 0)
        }
    }

    /**
     * Truncate a single file's diff.
     *
     * @param diffContent Raw unified diff. May be null / blank / binary / malformed.
     * @param budget Max characters to emit. Zero or negative returns [TruncationResult.EMPTY].
     * @param preserveBoundaries When true (default), keeps only complete hunks. When
     *     false, uses plain char-truncation that cuts at the last newline within `budget`.
     * @param contextLinesToKeep When set, strips leading/trailing context lines in
     *     each hunk down to the requested count before computing budget. Cannot
     *     grow the context - callers wanting more must fetch the diff at a higher
     *     `-U<n>` originally. Null (default) preserves every hunk as-is.
     */
    fun truncate(
        diffContent: String?,
        budget: Int,
        preserveBoundaries: Boolean = true,
        contextLinesToKeep: Int? = null,
    ): TruncationResult {
        if (diffContent.isNullOrBlank()) return TruncationResult.EMPTY
        if (budget <= 0) return TruncationResult.EMPTY

        val trimmed = diffContent.trimStart()
        if (trimmed.startsWith(HunkParser.BINARY_MARKER)) {
            val marker = "(binary file)"
            return TruncationResult(marker, 0, 0, marker.length)
        }
        if (HunkParser.hasSubmoduleMarker(diffContent)) {
            val marker = "(submodule change)"
            return TruncationResult(marker, 0, 0, marker.length)
        }

        val hunks = HunkParser.parseHunks(diffContent)
        if (hunks.isEmpty()) {
            return truncateRawBlock(diffContent, budget)
        }

        val adjusted = if (contextLinesToKeep != null) {
            hunks.map { stripContextLines(it, contextLinesToKeep.coerceAtLeast(0)) }
        } else {
            hunks
        }

        // A diff no longer than its budget is kept whole: hunk costs count line ends the raw text may not have.
        val effective = if (diffContent.length <= budget) Int.MAX_VALUE else budget
        return if (preserveBoundaries) {
            selectByScore(adjusted, effective)
        } else {
            truncateFlatPreserveOrder(adjusted, effective)
        }
    }

    /**
     * Re-strip the leading and trailing context of a hunk down to [keep] lines.
     * Internal-only except for testing - exposed via an internal helper file.
     */
    internal fun stripContextLines(
        hunk: HunkParser.DiffHunk,
        keep: Int
    ): HunkParser.DiffHunk {
        val lines = hunk.lines
        var leading = 0
        for (line in lines) {
            if (line.startsWith('+') || line.startsWith('-')) break
            leading++
        }
        var trailing = 0
        for (line in lines.asReversed()) {
            if (line.startsWith('+') || line.startsWith('-')) break
            trailing++
        }
        val leadingDrop = (leading - keep).coerceAtLeast(0)
        val trailingDrop = (trailing - keep).coerceAtLeast(0)
        val stripped = lines.drop(leadingDrop).dropLast(trailingDrop)
        val newContext = (hunk.contextLines - leadingDrop - trailingDrop).coerceAtLeast(0)
        return hunk.copy(lines = stripped, contextLines = newContext)
    }

    /** Treat input without `@@` headers as a single block subject to char truncation. */
    private fun truncateRawBlock(
        diffContent: String,
        budget: Int
    ): TruncationResult {
        val output = if (diffContent.length <= budget) {
            diffContent
        } else {
            diffContent.take(budget).substringBeforeLast('\n', missingDelimiterValue = diffContent.take(budget)) +
                "\n... (truncated)"
        }
        return TruncationResult(output, 0, 0, output.length)
    }

    /** Fallback mode that preserves original hunk order and cuts at hunk boundaries only. */
    private fun truncateFlatPreserveOrder(
        hunks: List<HunkParser.DiffHunk>,
        budget: Int,
    ): TruncationResult {
        val sb = StringBuilder()
        var kept = 0
        var omitted = 0
        var used = 0
        for (hunk in hunks) {
            if (used + hunk.totalChars <= budget) {
                sb.appendHunk(hunk)
                used += hunk.totalChars
                kept++
            } else {
                omitted++
            }
        }
        appendOmissionMarker(sb, omitted)
        val output = sb.toString().trimEnd()
        return TruncationResult(output, kept, omitted, output.length)
    }

    /**
     * Greedy: score each hunk, keep highest scoring first, stop when budget runs out.
     * Kept hunks are reordered back to their original file position before emit.
     */
    private fun selectByScore(
        hunks: List<HunkParser.DiffHunk>,
        budget: Int,
    ): TruncationResult {
        val scored = hunks
            .mapIndexed { index, hunk ->
                val positionBonus = POSITION_BONUS * (1.0 - index.toDouble() / hunks.size.coerceAtLeast(1))
                ScoredHunk(index = index, hunk = hunk, score = hunk.riskScore() + positionBonus)
            }.sortedByDescending { it.score }

        val kept = mutableListOf<ScoredHunk>()
        var used = 0
        for (candidate in scored) {
            if (used + candidate.hunk.totalChars <= budget) {
                kept += candidate
                used += candidate.hunk.totalChars
            }
        }
        kept.sortBy { it.index }
        val omitted = hunks.size - kept.size

        val sb = StringBuilder()
        kept.forEach { sb.appendHunk(it.hunk) }
        appendOmissionMarker(sb, omitted)
        val output = sb.toString().trimEnd()
        return TruncationResult(output, kept.size, omitted, output.length)
    }

    /**
     * Changed share of the hunk (added plus removed), plus a bonus when it removes a branch or a guard: a hunk that
     * mostly deletes code is the riskiest one, and scoring by added lines alone cut it first.
     */
    private fun HunkParser.DiffHunk.riskScore(): Double {
        val total = (addedLines + deletedLines + contextLines).coerceAtLeast(1)
        val removesGuard = lines.any { it.startsWith("-") && REMOVED_CONTROL_FLOW.containsMatchIn(it) }
        return (addedLines + deletedLines).toDouble() / total + if (removesGuard) CONTROL_FLOW_BONUS else 0.0
    }

    private fun StringBuilder.appendHunk(hunk: HunkParser.DiffHunk) {
        appendLine(hunk.header)
        hunk.lines.forEach { appendLine(it) }
    }

    private fun appendOmissionMarker(
        sb: StringBuilder,
        omitted: Int
    ) {
        if (omitted <= 0) return
        val marker = if (omitted == 1) {
            OMITTED_SUFFIX_SINGLE
        } else {
            OMITTED_SUFFIX_PLURAL_PREFIX + omitted + OMITTED_SUFFIX_PLURAL_SUFFIX
        }
        sb.appendLine(marker)
    }

    @Serializable
    private data class ScoredHunk(
        val index: Int,
        val hunk: HunkParser.DiffHunk,
        val score: Double
    )

    /** Small position bonus so earlier hunks break ties in their favour. */
    private const val POSITION_BONUS: Double = 0.1
    private const val CONTROL_FLOW_BONUS: Double = 0.5
    private val REMOVED_CONTROL_FLOW = Regex("""\b(if|guard|return|check|require|throw)\b|&&|\|\|""")
}
