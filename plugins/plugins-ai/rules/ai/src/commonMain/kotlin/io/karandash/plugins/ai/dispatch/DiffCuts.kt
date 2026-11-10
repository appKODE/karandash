package io.karandash.plugins.ai.dispatch

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.DiffChunk
import io.karandash.plugins.ai.core.DiffMode
import io.karandash.plugins.ai.core.DiffPreprocessor
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import io.karandash.plugins.ai.core.DroppedDiff
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.rules.DiffSectionBuilder
import kotlinx.serialization.Serializable

/**
 * One file the call sent short or not at all, in characters and `@@` hunks of the MR diff before and of the prompt
 * after. [how]: `hunk-window` (per-file cap kept whole hunks), `priority-truncation` (priority mode's per-file split or
 * `maxFilesToAnalyze`), `running-total` (`max-diff-chars-total` reached), `dropped-file` (past `max-chunks`),
 * `excluded:<reason>` (left out or collapsed by a filter before sizing).
 */
@Serializable
internal data class DiffCut(
    val file: String,
    val how: String,
    val charsBefore: Int,
    val charsAfter: Int,
    val hunksBefore: Int,
    val hunksAfter: Int,
)

/** The rule's diff going in (the MR's files with a diff, filters included) and what the calls sent. */
@Serializable
internal data class DiffTotals(
    val filesIn: Int,
    val charsIn: Int,
    val tokensIn: Int,
    val filesOut: Int,
    val charsOut: Int,
    val tokensOut: Int,
)

/** A chunk no call reviewed: [index] from 0, [tokens] its diff estimate. */
@Serializable
internal data class SkippedChunk(
    val index: Int,
    val files: List<String>,
    val tokens: Int,
    val reason: String,
)

internal object DiffCuts {
    const val HUNK_WINDOW = "hunk-window"
    const val PRIORITY = "priority-truncation"
    const val RUNNING_TOTAL = "running-total"
    const val DROPPED_FILE = "dropped-file"
    const val EXCLUDED = "excluded:"
    const val STUB_CUT = "excluded:deleted-file stub"
    const val BUDGET_EXHAUSTED = "token budget exhausted"
    const val TIMED_OUT = "timed out"
    private const val STUB_MARKER = "more deleted lines)"
    private const val OMITTED_LINE = "files omitted due to size limits"
    private val annotated = Regex("""^L\d+\(\+\) """, RegexOption.MULTILINE)

    /** Options of the prompt [DiffContextBuilder][io.karandash.plugins.ai.core.DiffContextBuilder] built. */
    class Options(
        val reviewMode: DiffMode,
        val preprocessor: DiffPreprocessorOptions,
        val priority: Boolean,
        val maxPerFile: Int,
    )

    /** Every file of [mr] the [preparedDiff] carries short or not at all, and why. */
    fun of(
        mr: MergeRequest,
        preparedDiff: String,
        options: Options,
    ): List<DiffCut> {
        val sections = sectionsOf(preparedDiff)
        return mr.changes.mapNotNull { change ->
            val raw = change.diffContent ?: return@mapNotNull null
            val before = raw.length
            val hunks = hunksOf(raw)
            val reason = DiffPreprocessor.exclusionReason(change, options.preprocessor)
            val kept = DiffPreprocessor.preprocess(listOf(change), options.preprocessor).changes.firstOrNull()
            val sent = sections[change.path]
            val wanted = kept?.let { DiffSectionBuilder.diffContentFor(it, options.reviewMode) }.orEmpty().trimEnd('\n')
            val sizingCut = if (options.priority) {
                PRIORITY
            } else if (wanted.length > options.maxPerFile) {
                HUNK_WINDOW
            } else {
                RUNNING_TOTAL
            }
            when {
                reason != null -> {
                    DiffCut(change.path, EXCLUDED + reason, before, 0, hunks, 0)
                }

                options.reviewMode == DiffMode.PATCH -> {
                    null
                }

                sent == null -> {
                    DiffCut(change.path, if (options.priority) PRIORITY else RUNNING_TOTAL, before, 0, hunks, 0)
                }

                hunksOf(sent) < hunksOf(wanted) || (!options.priority && sent.length < wanted.length) -> {
                    DiffCut(change.path, sizingCut, before, sent.length, hunks, hunksOf(sent))
                }

                change.changeType == ChangeType.DELETED && STUB_MARKER in wanted -> {
                    DiffCut(change.path, STUB_CUT, before, sent.length, hunks, hunksOf(sent))
                }

                else -> {
                    null
                }
            }
        }
    }

    /**
     * Totals of [mr] plus [excludedReasons] files going in (sized from [fullMr], the MR before preprocessing),
     * net of [allCuts] and [skipped] chunks going out.
     */
    fun totals(
        mr: MergeRequest,
        fullMr: MergeRequest?,
        excludedReasons: Map<String, String>,
        allCuts: List<DiffCut>,
        skipped: List<SkippedChunk>,
    ): DiffTotals {
        val excluded = excludedReasons.keys.associateWith { path ->
            fullMr
                ?.changes
                ?.firstOrNull { it.path == path }
                ?.diffContent
                ?.length ?: 0
        }
        val cuts = allCuts.filterNot { it.file in excluded }
        val withDiff = mr.changes.filter { it.diffContent != null }
        val charsIn = withDiff.sumOf { it.diffContent?.length ?: 0 } + excluded.values.sum()
        val gone = cuts.filter { it.charsAfter == 0 }.map { it.file } + excluded.keys + skipped.flatMap { it.files }
        val skippedChars = skipped.sumOf { TokenUnit.tokensToChars(it.tokens) }
        val charsOut = (charsIn - cuts.sumOf { it.charsBefore - it.charsAfter } - skippedChars).coerceAtLeast(0)
        val filesIn = withDiff.size + excluded.size
        return DiffTotals(
            filesIn = filesIn,
            charsIn = charsIn,
            tokensIn = TokenUnit.charsToTokens(charsIn),
            filesOut = (filesIn - gone.toSet().size).coerceAtLeast(0),
            charsOut = charsOut,
            tokensOut = TokenUnit.charsToTokens(charsOut),
        )
    }

    /** [excluded] (path to reason) as cuts, sized from [mr], the rule's MR before preprocessing. */
    fun excludedCuts(
        mr: MergeRequest?,
        excluded: Map<String, String>,
    ): List<DiffCut> {
        val byPath = mr?.changes.orEmpty().associateBy { it.path }
        return excluded.map { (path, reason) ->
            val raw = byPath[path]?.diffContent.orEmpty()
            DiffCut(path, EXCLUDED + reason, raw.length, 0, hunksOf(raw), 0)
        }
    }

    /** Files of [mr] with hunks past `max-chunks`: [dropped], sized by what [chunks] kept of each. */
    fun droppedCuts(
        mr: MergeRequest,
        chunks: List<DiffChunk>,
        dropped: List<DroppedDiff>,
    ): List<DiffCut> {
        val kept = chunks.flatMap { it.changes }.groupBy { it.path }
        val byPath = mr.changes.associateBy { it.path }
        return dropped.map { drop ->
            val windows = kept[drop.path].orEmpty().map { it.diffContent.orEmpty() }
            DiffCut(
                file = drop.path,
                how = DROPPED_FILE,
                charsBefore = byPath[drop.path]?.diffContent?.length ?: windows.sumOf { it.length },
                charsAfter = windows.sumOf { it.length },
                hunksBefore = drop.totalHunks,
                hunksAfter = drop.totalHunks - drop.droppedHunks,
            )
        }
    }

    /** Chunk [index] of [changes] as skipped for [reason]. */
    fun skipped(
        index: Int,
        changes: List<FileChange>,
        reason: String,
    ): SkippedChunk =
        SkippedChunk(
            index = index,
            files = changes.map { it.path }.distinct(),
            tokens = changes.sumOf { TokenUnit.tokensOf(it.diffContent.orEmpty()) },
            reason = reason,
        )

    /** Chunks no call reviewed: over [chunkChars], past the rule deadline ([timedOut]), or refused by the budget. */
    fun skippedChunks(
        chunks: List<DiffChunk>,
        fits: List<Boolean>,
        sent: List<Boolean>,
        chunkChars: Int,
        timedOut: Set<Int> = emptySet(),
    ): List<SkippedChunk> =
        chunks.mapIndexedNotNull { index, chunk ->
            when {
                !fits[index] -> skipped(index, chunk.changes, "over the $chunkChars characters one call holds")
                index in timedOut -> skipped(index, chunk.changes, TIMED_OUT)
                !sent[index] -> skipped(index, chunk.changes, BUDGET_EXHAUSTED)
                else -> null
            }
        }

    /** Count of cuts by [DiffCut.how], for one WARN per kind. */
    fun countsByKind(cuts: List<DiffCut>): Map<String, Int> = cuts.groupingBy { it.how }.eachCount()

    private fun hunksOf(diff: String): Int = diff.lineSequence().count { it.startsWith("@@") }

    /** `### path` sections of [preparedDiff], line-number annotations undone so sizes compare with the MR diff. */
    private fun sectionsOf(preparedDiff: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        val parts = ("\n" + preparedDiff).split("\n### ").drop(1)
        for (part in parts) {
            val path = part.substringBefore('\n')
            val body = part.substringAfter('\n', "").lines().filterNot { it.startsWith("(") && OMITTED_LINE in it }
            sections[path] = annotated.replace(body.joinToString("\n").trimEnd('\n'), "+")
        }
        return sections
    }
}
