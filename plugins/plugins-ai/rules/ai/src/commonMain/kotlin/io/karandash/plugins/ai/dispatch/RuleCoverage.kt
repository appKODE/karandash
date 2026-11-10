package io.karandash.plugins.ai.dispatch

import io.karandash.api.finding.ReviewCoverage
import io.karandash.models.FileChange
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.Serializable

/**
 * What one AI rule sent to the model in one run.
 *
 * @property files Changed files with a diff.
 * @property reviewed Files in a chunk the analyzer actually reviewed (not budget-skipped, not self-skipped).
 * @property truncated Reviewed files whose diff exceeded the per-file or per-chunk character limit, or that the
 *   call's running diff total (`maxDiffCharsTotal`) cut short.
 * @property chunksSkipped Chunks not reviewed: the token budget could not cover them, or the analyzer skipped them itself.
 * @property selfSkipReason Reason the rule did not run: the analyzer declined (`AiAnalyzerContext.skip`) or its
 *   T0 prompt alone is over `ai.budget.max-input-tokens`; null when it ran.
 * @property dropped Diff the budget or the chunk limit left out of the prompt: a bare path for a file left out whole
 *   (never in [reviewed]), `path (N of M hunks)` for a file whose other hunks were reviewed.
 * @property contextDropped T2 context sections dropped whole to fit `ai.budget.max-input-tokens`, and those cut to
 *   their own cap, named `<section> (cut to N of M chars)`.
 * @property excluded Files the diff preprocessor dropped whole, named `path (reason)`; never budget drops.
 */
@Serializable
internal data class RuleCoverage(
    val files: Set<String>,
    val reviewed: Set<String>,
    val truncated: Set<String> = emptySet(),
    val chunks: Int = 1,
    val chunksSkipped: Int = 0,
    val selfSkipReason: String? = null,
    val dropped: Set<String> = emptySet(),
    val contextDropped: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
) {
    /** Reason the rule did no review at all, or null when it reviewed something. */
    val skipReason: String?
        get() = when {
            reviewed.isNotEmpty() -> null
            selfSkipReason != null -> selfSkipReason
            chunksSkipped > 0 -> BUDGET_EXHAUSTED
            else -> null
        }

    companion object {
        const val BUDGET_EXHAUSTED = "budget exhausted"

        /**
         * Run-level coverage over all AI rules: a file counts as reviewed when any rule reviewed it, and as dropped
         * only when no rule did; null when no rule ran.
         */
        fun merge(coverages: Collection<RuleCoverage>): ReviewCoverage? =
            if (coverages.isEmpty()) {
                null
            } else {
                coverages.flatMap { it.reviewed }.toSet().let { reviewed ->
                    ReviewCoverage(
                        totalFiles = coverages.flatMap { it.files }.toSet().size,
                        reviewedFiles = reviewed.size,
                        chunksSkipped = coverages.sumOf { it.chunksSkipped },
                        truncatedFiles = coverages.flatMap { it.truncated }.toSet().size,
                        dropped = (coverages.flatMap { it.dropped }.toSet() - reviewed).sorted(),
                        contextDropped = coverages.flatMap { it.contextDropped }.toSet().sorted(),
                        excluded = coverages.flatMap { it.excluded }.toSet().sorted(),
                    )
                }
            }

        /** Files of [reviewed] (in order) whose diff ends past [totalCap] characters: the call's running total cut them. */
        fun cutByTotal(
            reviewed: List<FileChange>,
            totalCap: Int,
        ): Set<String> {
            // limit: running total over raw diff lengths in MR order; the builder sums after preprocessing, so a
            // preprocessor that shrinks diffs can make this name a file the call sent whole.
            val ends = reviewed.runningFold(0L) { end, file -> end + (file.diffContent?.length ?: 0) }.drop(1)
            return reviewed.filterIndexed { index, _ -> ends[index] > totalCap }.map { it.path }.toSet()
        }

        // limit: "truncated" = diff longer than the char limit, not what DiffContextBuilder actually cut;
        // report the builder's own cut list if the proxy proves misleading.

        /**
         * [reviewed] is what the analyzer saw, already net of each call's own omissions, so a file one window
         * reviewed stays reviewed and is not named whole in [dropped].
         */
        fun of(
            all: List<FileChange>,
            reviewed: List<FileChange>,
            chunks: Int,
            chunksSkipped: Int,
            truncateAbove: Int,
            selfSkipReason: String? = null,
            dropped: Set<String> = emptySet(),
            contextDropped: Set<String> = emptySet(),
            excluded: Map<String, String> = emptyMap(),
            totalCap: Int = Int.MAX_VALUE,
        ): RuleCoverage {
            val withDiff = reviewed.filter { it.diffContent != null }
            val reviewedPaths = withDiff.map { it.path }.toSet()
            return RuleCoverage(
                files = all.filter { it.diffContent != null }.map { it.path }.toSet(),
                reviewed = reviewedPaths,
                truncated = withDiff.filter { (it.diffContent?.length ?: 0) > truncateAbove }.map { it.path }.toSet() +
                    cutByTotal(withDiff, totalCap),
                chunks = chunks,
                chunksSkipped = chunksSkipped,
                selfSkipReason = selfSkipReason,
                dropped = dropped - reviewedPaths,
                contextDropped = contextDropped,
                excluded = excluded.map { (path, reason) -> "$path ($reason)" }.toSet(),
            )
        }
    }
}

/**
 * Skip reasons and diff files left out, reported by analyzer calls; these may race across concurrent chunks,
 * so both are copy-on-write.
 */
internal class SkipLog {
    private val ref = atomic<List<String>>(emptyList())
    private val omittedRef = atomic<Set<String>>(emptySet())
    private val excludedRef = atomic<Map<String, String>>(emptyMap())
    private val cutsRef = atomic<List<DiffCut>>(emptyList())
    private val skippedRef = atomic<List<SkippedChunk>>(emptyList())
    private val refusalsRef = atomic<List<BudgetRefusal>>(emptyList())
    private val sampleTokensRef = atomic(0)
    val reasons: List<String> get() = ref.value
    val omitted: Set<String> get() = omittedRef.value

    /** Files the diff preprocessor dropped whole, path to reason. */
    val excluded: Map<String, String> get() = excludedRef.value

    fun add(reason: String) {
        ref.update { it + reason }
    }

    fun addOmitted(paths: Collection<String>) {
        omittedRef.update { it + paths }
    }

    fun addExcluded(paths: Map<String, String>) {
        excludedRef.update { it + paths }
    }

    /** Files the calls sent short or not at all, for the `plan.json` dump. */
    val cuts: List<DiffCut> get() = cutsRef.value

    /** Chunks no call reviewed, for the `plan.json` dump. */
    val skippedChunks: List<SkippedChunk> get() = skippedRef.value

    fun addCuts(cuts: List<DiffCut>) {
        cutsRef.update { it + cuts }
    }

    fun addSkipped(chunks: List<SkippedChunk>) {
        skippedRef.update { it + chunks }
    }

    /** Calls the budget gate refused, for `ai/budget-plan.json`. */
    val refusals: List<BudgetRefusal> get() = refusalsRef.value

    fun addRefusal(refusal: BudgetRefusal) {
        refusalsRef.update { it + refusal }
    }

    /** Tokens the extra review-comments samples spent on their own budget, never the run budget's. */
    val sampleTokens: Int get() = sampleTokensRef.value

    fun addSampleTokens(tokens: Int) {
        sampleTokensRef.addAndGet(tokens)
    }

    /** Whether the analyzer saw [path]: neither left out for the budget nor excluded by the preprocessor. */
    fun saw(path: String): Boolean = path !in omitted && path !in excluded
}
