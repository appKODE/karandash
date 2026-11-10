package io.karandash.plugins.ai.core

import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.DiffSectionBuilder
import io.karandash.plugins.ai.core.rules.TruncationStrategy
import io.karandash.utils.mr.HunkParser

/**
 * Builds a [PrecomputedDiffContext] from a merge request and AI config.
 *
 * Metadata assembly and diff truncation shared by the AI analyzer modules.
 */
object DiffContextBuilder {
    /**
     * Builds the precomputed diff context for one MR dispatch.
     *
     * Two-argument overload - always uses the sequential
     * truncation path. New callers that want priority-based smart truncation should
     * pass [AiContextQualityConfig] explicitly via the three-argument overload.
     *
     * @param mr The (already redacted) merge request.
     * @param aiConfig Runtime AI rule configuration controlling diff size limits.
     * @return A fully populated [PrecomputedDiffContext].
     */
    fun build(
        mr: MergeRequest,
        aiConfig: AiRuleConfig,
    ): PrecomputedDiffContext =
        build(
            mr = mr,
            aiConfig = aiConfig,
            contextQuality = null,
            enrichment = null,
            diagnostics = null,
        )

    /**
     * Builds the precomputed diff context with optional smart-truncation support.
     *
     * When [contextQuality]'s truncation strategy is [TruncationStrategy.PRIORITY],
     * files are scored via [FilePriorityScorer], budget is allocated by
     * [TokenBudget.splitAcrossFiles] and each file's diff is truncated at hunk boundaries by
     * [HunkAwareTruncator]. Otherwise - including when [contextQuality] is null or
     * disabled - the sequential [DiffSectionBuilder.build] path is used.
     *
     * @param mr The (already redacted) merge request.
     * @param aiConfig Runtime AI rule configuration controlling diff size limits.
     * @param contextQuality Optional smart-truncation configuration.
     * @param enrichment Optional enrichment context supplying the hotspot signal.
     * @param diagnostics Optional telemetry sink for truncation decisions.
     * @param reviewMode Which content of each file is sent (`ai.reviewMode`), on both truncation paths.
     * @return A fully populated [PrecomputedDiffContext].
     */
    fun build(
        mr: MergeRequest,
        aiConfig: AiRuleConfig,
        contextQuality: AiContextQualityConfig?,
        enrichment: EnrichmentContext? = null,
        diagnostics: ContextQualityDiagnostics? = null,
        reviewMode: DiffMode = DiffMode.CHANGED,
    ): PrecomputedDiffContext {
        val usePriority = contextQuality != null &&
            contextQuality.enabled &&
            contextQuality.truncation.strategy == TruncationStrategy.PRIORITY
        val reviewed = mr.copy(changes = capFiles(mr.changes, contextQuality, enrichment))
        val rawDiff = if (usePriority) {
            buildPriorityDiff(reviewed, aiConfig, requireNotNull(contextQuality), enrichment, diagnostics, reviewMode)
        } else {
            DiffSectionBuilder.build(
                changes = reviewed.changes,
                maxDiffCharsTotal = aiConfig.maxDiffCharsTotal,
                maxDiffCharsPerFile = aiConfig.maxDiffCharsPerFile,
                diffMode = reviewMode,
                preprocessorOptions = aiConfig.diffPreprocessor,
            )
        }

        // Annotate `+` lines with new-file line numbers so the LLM has an explicit
        // anchor for review-comment line references instead of having to count `+`
        // lines itself - this was a major source of off-by-many line errors in
        // inline AI comments (see plans/i-see-that-sometimes-magical-candy.md).
        val preparedDiff = HunkParser.annotateAddedLinesWithNumbers(rawDiff)

        val metadata = buildMetadata(mr)
        val fullDiff = buildFullDiff(mr)
        val fullDiffCharCount = mr.changes.sumOf { it.diffContent?.length ?: 0 }
        val omittedFileCount = omittedPaths(mr, preparedDiff, reviewMode, aiConfig.diffPreprocessor).size

        return PrecomputedDiffContext(
            metadata = metadata,
            preparedDiff = preparedDiff,
            fullDiff = fullDiff,
            omittedFileCount = omittedFileCount,
            fullDiffCharCount = fullDiffCharCount,
        )
    }

    /**
     * `ai.contextQuality.maxFilesToAnalyze` keeps that many files, the highest [FilePriorityScorer] scores,
     * in their MR order; the rest are reported as omitted. Unset (or context quality disabled) keeps every file.
     */
    private fun capFiles(
        changes: List<FileChange>,
        contextQuality: AiContextQualityConfig?,
        enrichment: EnrichmentContext?,
    ): List<FileChange> {
        val max = contextQuality?.takeIf { it.enabled }?.maxFilesToAnalyze ?: return changes
        if (changes.size <= max) return changes
        val kept = FilePriorityScorer
            .score(changes, contextQuality.truncation.priority, enrichment)
            .take(max)
            .mapTo(mutableSetOf()) { it.change.path }
        return changes.filter { it.path in kept }
    }

    /**
     * Priority-based diff construction. Orchestrates [DiffPreprocessor],
     * [FilePriorityScorer], [TokenBudget.splitAcrossFiles], and [HunkAwareTruncator]. Emits
     * one [ContextQualityDiagnostics.truncationDecision] log per file.
     */
    private fun buildPriorityDiff(
        mr: MergeRequest,
        aiConfig: AiRuleConfig,
        contextQuality: AiContextQualityConfig,
        enrichment: EnrichmentContext?,
        diagnostics: ContextQualityDiagnostics?,
        reviewMode: DiffMode,
    ): String {
        val preprocessed = DiffPreprocessor
            .preprocess(
                changes = mr.changes,
                options = aiConfig.diffPreprocessor,
            ).changes
        if (preprocessed.isEmpty()) return ""

        val truncation = contextQuality.truncation
        val scored = FilePriorityScorer.score(preprocessed, truncation.priority, enrichment)
        if (scored.isEmpty()) return ""

        val allocations = TokenBudget.splitAcrossFiles(
            scoredFiles = scored,
            totalBudget = aiConfig.maxDiffCharsTotal,
            maxPerFile = aiConfig.maxDiffCharsPerFile,
            allocation = truncation.budgetAllocation,
            diagnostics = diagnostics,
            // The size DiffChunker plans by, so a chunk it sized to fit reaches the prompt whole.
            needOf = { DiffSectionBuilder.diffContentFor(it.change, reviewMode)?.length ?: 0 },
        )
        val allocByPath = allocations.associateBy { it.path }

        val sb = StringBuilder()
        for (entry in scored) {
            val allocation = allocByPath[entry.change.path] ?: continue
            val result = HunkAwareTruncator.truncate(
                diffContent = DiffSectionBuilder.diffContentFor(entry.change, reviewMode),
                budget = allocation.budget,
                preserveBoundaries = truncation.preserveHunkBoundaries,
            )
            diagnostics?.truncationDecision(
                path = entry.change.path,
                strategy = truncation.strategy.name,
                fileBudget = allocation.budget,
                keptHunks = result.keptHunks,
                droppedHunks = result.omittedHunks,
                charsPerToken = truncation.tokenEstimator.charsPerToken,
            )
            if (result.output.isBlank()) continue
            if (reviewMode != DiffMode.PATCH) sb.appendLine("### ${entry.change.path}")
            sb.appendLine(result.output)
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun buildMetadata(mr: MergeRequest): String = mrMetadata(mr)

    /**
     * MR metadata block for prompts. With [reviewedFiles] (a filtered or chunked run reviewing only part of
     * [mr]) the file count reads `Files changed: <all> (in this review: <reviewed>)`, so MR-level analyzers
     * such as risk scoring see whole-MR statistics.
     */
    fun mrMetadata(
        mr: MergeRequest,
        reviewedFiles: Int? = null,
    ): String =
        buildString {
            appendLine("Title: ${mr.title}")
            appendLine("Description: ${mr.description ?: "(none)"}")
            appendLine("Author: ${mr.author.name}")
            appendLine("Source branch: ${mr.sourceBranch}")
            appendLine("Target branch: ${mr.targetBranch}")
            appendLine("Files changed: ${mr.changes.size}" + (reviewedFiles?.let { " (in this review: $it)" } ?: ""))
            appendLine("Additions: ${mr.changes.sumOf { it.additions }}")
            appendLine("Deletions: ${mr.changes.sumOf { it.deletions }}")
            appendLine()
            mr.changes.forEach { change ->
                appendLine("- ${change.path} (+${change.additions}/-${change.deletions})")
            }
        }

    private fun buildFullDiff(mr: MergeRequest): String =
        buildString {
            for (change in mr.changes) {
                val diff = change.diffContent ?: continue
                appendLine("### ${change.path}")
                appendLine(diff)
            }
        }

    /**
     * Files in [mr] that have diff content but no `### path` header in [preparedDiff]: the diff budget left them out.
     * Files the [preprocessor] drops whole are [excludedPaths], not budget omissions. PATCH mode writes no headers,
     * so it reports none.
     */
    fun omittedPaths(
        mr: MergeRequest,
        preparedDiff: String,
        reviewMode: DiffMode = DiffMode.CHANGED,
        preprocessor: DiffPreprocessorOptions = DiffPreprocessorOptions(),
    ): List<String> {
        if (reviewMode == DiffMode.PATCH) return emptyList()
        val headers = preparedDiff.lineSequence().filter { it.startsWith("### ") }.toSet()
        return mr.changes
            .filter { it.diffContent != null && "### ${it.path}" !in headers }
            .filter { DiffPreprocessor.exclusionReason(it, preprocessor) == null }
            .map { it.path }
    }

    /** Files in [mr] with diff content that the [preprocessor] drops whole, each mapped to why (`deleted`, `binary`, …). */
    fun excludedPaths(
        mr: MergeRequest,
        preprocessor: DiffPreprocessorOptions,
    ): Map<String, String> =
        mr.changes
            .filter { it.diffContent != null }
            .mapNotNull { change -> DiffPreprocessor.exclusionReason(change, preprocessor)?.let { change.path to it } }
            .toMap()
}
