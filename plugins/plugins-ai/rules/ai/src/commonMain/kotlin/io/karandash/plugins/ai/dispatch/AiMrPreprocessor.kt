package io.karandash.plugins.ai.dispatch

import io.karandash.ai.pipeline.AiTrace
import io.karandash.logger.Logger
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.DiffPreprocessor
import io.karandash.plugins.ai.core.HunkExpander
import io.karandash.plugins.ai.core.rules.AiPartialReviewPolicy
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.PartialReviewStrategy
import io.karandash.state.snapshot.DeltaCheckResult
import io.karandash.state.snapshot.SnapshotManager
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable

/** Result of [AiMrPreprocessor.preprocessMrForRule]. */
internal sealed class PreprocessResult {
    @Serializable
    data class Ready(
        val mr: MergeRequest,
        /** Files left out of [mr] whole, path to reason, for the coverage ledger. */
        val excluded: Map<String, String> = emptyMap(),
    ) : PreprocessResult()

    @Serializable
    data class Skipped(
        val reason: String
    ) : PreprocessResult()
}

/**
 * Handles MR preprocessing and file-filtering steps prior to AI dispatch.
 *
 * Each method takes the data it needs as explicit parameters so that it has
 * no dependency on [io.karandash.plugins.ai.init.InitState] or module-level
 * atomic state.
 */
internal class AiMrPreprocessor(
    private val logger: Logger,
    /** Files left out of a rule's diff this run, for the `ai-incremental` summary. */
    val skippedFiles: SkippedFilesCollector = SkippedFilesCollector(),
) {
    /**
     * Runs the full preprocessing pipeline for a single rule:
     * file-filter → exclude-policy → diff-preprocessor → delta-check → partial-review → hunk-expansion.
     *
     * Partial review runs after the delta so its file limit picks among files that changed since the last review,
     * never spending the limit on unchanged files the delta then drops.
     *
     * @return [PreprocessResult.Ready] with the filtered MR and the files left out of it whole, or
     *         [PreprocessResult.Skipped] when the delta check determines the rule should be skipped.
     */
    fun preprocessMrForRule(
        ruleId: String,
        redactedMr: MergeRequest,
        aiRuleConfig: AiRuleConfig,
        snapshotManager: SnapshotManager,
    ): PreprocessResult {
        val fileFilteredMr = filterChangesForRule(ruleId, redactedMr, aiRuleConfig)
        val excludedMr = applyExcludePolicy(fileFilteredMr, aiRuleConfig)
        val preprocessedMr = applyDiffPreprocessorFileFilters(preprocessedMr = excludedMr, aiRuleConfig = aiRuleConfig)
        val deltaMr = when (val deltaCheck = snapshotManager.checkDelta(ruleId, preprocessedMr)) {
            is DeltaCheckResult.Skip -> return PreprocessResult.Skipped(deltaCheck.reason)
            is DeltaCheckResult.Proceed -> deltaCheck.filteredMr
        }
        val (partialMr, partialReason) = applyPartialReview(deltaMr, aiRuleConfig)
        val filteredMr = expandHunks(partialMr, aiRuleConfig)
        if (partialReason != null) {
            logger.info("AI plugin [$ruleId]: $partialReason")
        }
        val excludedPaths = excludedMr.changes.mapTo(mutableSetOf()) { it.path }
        val preprocessedPaths = preprocessedMr.changes.mapTo(mutableSetOf()) { it.path }
        val partialPaths = filteredMr.changes.mapTo(mutableSetOf()) { it.path }
        val partialDropped = deltaMr.changes.map { it.path } - partialPaths
        skippedFiles.add(
            tooLarge = partialDropped,
            filtered = redactedMr.changes.map { it.path } - excludedPaths,
            binary = excludedMr.changes.filter { it.path !in preprocessedPaths && DiffPreprocessor.isBinaryDiff(it) }.map { it.path },
        )
        val policy = aiRuleConfig.excludePolicy.patterns
        val excluded = fileFilteredMr.changes.filter { it.path !in excludedPaths }.associate { change ->
            change.path to (policy.firstOrNull { GlobMatcher.matches(change.path, it) }?.let { "matched $it" } ?: "excluded")
        } + excludedMr.changes.filter { it.path !in preprocessedPaths }.associate { change ->
            change.path to (DiffPreprocessor.exclusionReason(change, aiRuleConfig.diffPreprocessor) ?: "preprocessed out")
        } + (preprocessedPaths - deltaMr.changes.mapTo(mutableSetOf()) { it.path }).associateWith { UNCHANGED } +
            partialDropped.associateWith { _ ->
                aiRuleConfig.partialReviewPolicy?.let { "partial review: over ${it.maxFiles} files" } ?: "partial review"
            }
        val totalChars = filteredMr.changes.sumOf { it.diffContent?.length ?: 0 }
        val head = "AI plugin [$ruleId]: diff prepared ${filteredMr.changes.size} file(s) ($totalChars chars total)"
        // The per-file list repeats for every AI rule: only with --trace / KARANDASH_AI_TRACE=1
        if (AiTrace.enabled) {
            val files = filteredMr.changes.joinToString("\n") { change ->
                "  ${change.path}: ${change.diffContent?.length ?: 0} chars (+${change.additions}/-${change.deletions})"
            }
            logger.debug(AiTrace.section("ai_diff_$ruleId", "$head:", files))
        } else {
            logger.debug(head)
        }
        return PreprocessResult.Ready(filteredMr, excluded)
    }

    /** `ai.contextQuality.hunk-expansion`: widens each kept file's hunks from its head content, so chunking sizes them. */
    private fun expandHunks(
        mr: MergeRequest,
        aiRuleConfig: AiRuleConfig,
    ): MergeRequest {
        val config = aiRuleConfig.contextQuality
            .takeIf { it.enabled }
            ?.hunkExpansion
            ?.takeIf { it.enabled } ?: return mr
        return mr.copy(changes = mr.changes.map { HunkExpander.expand(it, config) })
    }

    /**
     * Excludes noise files from AI analysis based on glob patterns.
     *
     * Applied unconditionally (regardless of whether partial review is enabled).
     */
    fun applyExcludePolicy(
        mr: MergeRequest,
        aiRuleConfig: AiRuleConfig
    ): MergeRequest {
        val policy = aiRuleConfig.excludePolicy
        if (policy.patterns.isEmpty()) return mr

        logger.debug(
            "AI size-policy [exclude]: checking ${mr.changes.size} file(s) against ${policy.patterns.size} pattern(s)",
        )
        val before = mr.changes.size
        val filtered = mr.changes.filter { change ->
            val matchedPattern = policy.patterns.firstOrNull { pattern -> GlobMatcher.matches(change.path, pattern) }
            if (matchedPattern != null) {
                logger.debug("  excluded ${change.path} (matched: $matchedPattern)")
            }
            matchedPattern == null
        }
        val excluded = before - filtered.size
        if (excluded > 0) {
            logger.debug("AI size-policy [exclude]: excluded $excluded of $before file(s)")
        }
        return if (excluded > 0) mr.copy(changes = filtered) else mr
    }

    /**
     * Applies file-level diff preprocessor filters: removes deleted files, pure renames, and binary diffs, and cuts
     * kept deleted files to their stub, so the chunker sizes what is sent.
     *
     * Content-level transforms (strip deleted lines, metadata) are applied later in the diff builder.
     */
    fun applyDiffPreprocessorFileFilters(
        preprocessedMr: MergeRequest,
        aiRuleConfig: AiRuleConfig
    ): MergeRequest {
        val options = aiRuleConfig.diffPreprocessor
        logger.debug(
            "AI size-policy [diff-preprocessor]: skipDeleted=${options.skipDeletedFiles}" +
                ", skipRenames=${options.skipPureRenames}" +
                ", skipBinary=${options.skipBinaryDiffs}" +
                ", stripMeta=${options.stripDiffMetadata}" +
                " — processing ${preprocessedMr.changes.size} file(s)",
        )
        val fileOnlyOptions = options.copy(
            stripDeletedLines = false,
            stripDiffMetadata = false,
        )
        val result = DiffPreprocessor.preprocess(preprocessedMr.changes, fileOnlyOptions, logger)
        val summary = result.stats.summary()
        if (summary != null) {
            logger.debug("AI plugin: $summary")
        }
        return if (result.changes == preprocessedMr.changes) preprocessedMr else preprocessedMr.copy(changes = result.changes)
    }

    /**
     * Applies partial review policy to limit the number of files sent to the AI.
     *
     * @return A pair of (possibly filtered MR, optional log message describing what was done).
     */
    fun applyPartialReview(
        mr: MergeRequest,
        aiRuleConfig: AiRuleConfig
    ): Pair<MergeRequest, String?> {
        val policy = aiRuleConfig.partialReviewPolicy ?: return mr to null
        if (mr.changes.size <= policy.maxFiles) {
            logger.debug(
                "AI size-policy [partial-review]: ${mr.changes.size} file(s) within maxFiles=${policy.maxFiles}" +
                    " — no reduction needed",
            )
            return mr to null
        }

        logger.debug(
            "AI size-policy [partial-review]: ${mr.changes.size} file(s) exceeds maxFiles=${policy.maxFiles}" +
                " — applying strategy=${policy.strategy.name.lowercase()}",
        )

        val selected = when (policy.strategy) {
            PartialReviewStrategy.MOST_CHANGED -> {
                mr.changes.sortedByDescending { it.additions + it.deletions }.take(policy.maxFiles)
            }

            PartialReviewStrategy.FIRST -> {
                mr.changes.take(policy.maxFiles)
            }

            PartialReviewStrategy.BY_TYPE -> {
                selectByType(mr.changes, policy)
            }
        }

        val selectedPaths = selected.map { it.path }.toSet()
        val droppedFiles = mr.changes.filter { it.path !in selectedPaths }
        for (dropped in droppedFiles) {
            logger.debug("  partial-review: dropped ${dropped.path} (+${dropped.additions}/-${dropped.deletions})")
        }

        val reason = "Partial review: selected ${selected.size} of ${mr.changes.size} files " +
            "(strategy=${policy.strategy.name.lowercase()})"
        return mr.copy(changes = selected) to reason
    }

    private fun selectByType(
        candidates: List<FileChange>,
        policy: AiPartialReviewPolicy
    ): List<FileChange> {
        val prioritized = candidates.filter { change ->
            policy.prioritizeTypes.any { pattern -> GlobMatcher.matches(change.path, pattern) }
        }
        val sortedPrioritized = prioritized.sortedByDescending { it.additions + it.deletions }

        if (sortedPrioritized.size >= policy.maxFiles) {
            return sortedPrioritized.take(policy.maxFiles)
        }

        val prioritizedPaths = prioritized.map { it.path }.toSet()
        val remaining = candidates
            .filter { it.path !in prioritizedPaths }
            .sortedByDescending { it.additions + it.deletions }

        return (sortedPrioritized + remaining).take(policy.maxFiles)
    }

    /**
     * Filters MR changes by per-rule and global include/exclude glob patterns.
     */
    fun filterChangesForRule(
        ruleId: String,
        mr: MergeRequest,
        aiRuleConfig: AiRuleConfig
    ): MergeRequest {
        val globalInclude = aiRuleConfig.fileFilters
        val globalExclude = aiRuleConfig.excludeFilters
        val ruleInclude = aiRuleConfig.ruleFileFilters[ruleId] ?: emptyList()
        val ruleExclude = aiRuleConfig.ruleExcludeFilters[ruleId] ?: emptyList()

        val include = (globalInclude + ruleInclude).distinct()
        val exclude = (globalExclude + ruleExclude).distinct()

        if (include.isEmpty() && exclude.isEmpty()) return mr

        val filtered = mr.changes.filter { change ->
            val matchesInclude = include.isEmpty() || include.any { GlobMatcher.matches(change.path, it) }
            val matchesExclude = exclude.any { GlobMatcher.matches(change.path, it) }
            matchesInclude && !matchesExclude
        }
        return mr.copy(changes = filtered)
    }
}

/** Excluded reason of a file the delta check drops: its diff did not change since the last reviewed run. */
private const val UNCHANGED = "unchanged since the last review"
