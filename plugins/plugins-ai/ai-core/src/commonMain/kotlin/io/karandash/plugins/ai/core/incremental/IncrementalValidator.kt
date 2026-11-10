package io.karandash.plugins.ai.core.incremental

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.sdk.Logger
import io.karandash.state.snapshot.DeltaResult
import io.karandash.state.snapshot.DiffDeltaCalculator
import io.karandash.state.snapshot.SnapshotManager
import io.karandash.utils.finding.AnchorFingerprint
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.serialization.Serializable

/**
 * Coordinates incremental AI validation by tracking file diff snapshots and
 * caching per-file AI findings across pipeline runs.
 *
 * On each run the validator:
 * 1. Loads the previous snapshot for the MR.
 * 2. Computes which files have changed since that snapshot.
 * 3. Returns cached findings for unchanged files plus a delta MR with only changed files.
 *
 * If the MR is fully unchanged ([IncrementalContext.isFullyCached] is true),
 * the caller may skip validation entirely and serve the cached findings.
 *
 * After validation, call [save] to persist the updated snapshot and per-file findings.
 *
 * @param persistence The persistence layer for snapshots and findings caches.
 * @param logger Logger for debug-level incremental state diagnostics.
 */
class IncrementalValidator(
    private val persistence: AiStatePersistence,
    private val logger: Logger,
) {
    /**
     * Prepares an [IncrementalContext] for the current MR run.
     *
     * Loads the previous snapshot, computes the file delta, and retrieves
     * cached findings for all unchanged files.
     *
     * @param mr The current merge request.
     * @param configHash Hash of the effective configuration for this run. Stored in the snapshot
     *   so a config change invalidates every cached finding on the next run. `null` disables the
     *   config dimension.
     * @return An [IncrementalContext] describing what has and has not changed.
     */
    fun prepare(
        mr: MergeRequest,
        configHash: Long? = null,
    ): IncrementalContext {
        require(mr.id.isNotBlank()) { "MergeRequest.id must not be blank for incremental validation" }
        val mrKey = SnapshotManager.mrKey(mr)
        val storedSnapshot = persistence.loadSnapshot(mrKey)
        val lastReviewedSha = if (storedSnapshot.isNotEmpty()) persistence.loadLastReviewedSha(mrKey) else null
        val fullReviewReason = if (storedSnapshot.isEmpty()) null else historyBreak(mr, lastReviewedSha)
        // A rewritten history leaves the stored snapshot describing commits the MR no longer has: review in full.
        val previousSnapshot = if (fullReviewReason == null) storedSnapshot else emptyMap()
        fullReviewReason?.let { logger.info("Incremental: full review, $it") }
        if (previousSnapshot.isNotEmpty()) {
            logger.debug("AI plugin: incremental snapshot loaded for $mrKey — ${previousSnapshot.size} file(s)")
        } else {
            logger.debug("AI plugin: incremental no prior snapshot for $mrKey")
        }
        val delta = DiffDeltaCalculator.delta(mr, previousSnapshot, configHash)
        val changedFiles = delta.mr.changes.map { it.path }
        val addedFiles = changedFiles.filter { it !in previousSnapshot }
        val modifiedFiles = changedFiles.filter { it in previousSnapshot }
        logger.debug(
            "Cache delta: ${delta.unchangedFiles.size} unchanged, " +
                "${modifiedFiles.size} modified, ${addedFiles.size} added",
        )
        if (modifiedFiles.isNotEmpty()) {
            logger.debug("  Modified files: $modifiedFiles")
        }
        if (addedFiles.isNotEmpty()) {
            logger.debug("  Added files: $addedFiles")
        }
        val currentSnapshot = DiffDeltaCalculator.snapshot(mr, configHash)
        // Load MR-level findings only when metadata is unchanged - a metadata change means MR-level
        // findings are stale and must be re-evaluated (e.g. title changed → titleFormat re-runs).
        val includeMrLevel = previousSnapshot.isNotEmpty() && !delta.metadataChanged
        val fullCache = if (previousSnapshot.isNotEmpty()) persistence.loadFindingsCache(mrKey) else emptyMap()
        val cachedFindings = buildCachedFindings(fullCache, delta.unchangedFiles, includeMrLevel = includeMrLevel)
        if (cachedFindings.isNotEmpty()) {
            logger.debug(
                "Cache reuse: ${cachedFindings.size} finding(s) from ${delta.unchangedFiles.size} unchanged file(s)",
            )
        }
        // Prior findings for CHANGED files and prior MR-level findings - surfaced for the AI
        // prompt-context channel independent of the restore-into-report gate above. Always loaded
        // (when any prior cache exists) so analyzers can show the model what was previously
        // reported, even on metadata-only changes.
        val changedPaths = changedFiles.toSet()
        val priorFindingsByChangedFile = fullCache
            .filterKeys { it != MR_LEVEL_KEY && it in changedPaths }
        val priorMrLevelFindings = fullCache[MR_LEVEL_KEY].orEmpty()
        // hasCacheEntry: true only when ALL cache dimensions are valid.
        // Metadata change → MR-level findings stale → not fully cached.
        // Config change → all findings stale → not fully cached.
        // Rules that did not complete last run review the whole MR again, so the MR is never a full hit while any remain.
        val pendingRules = if (previousSnapshot.isNotEmpty()) persistence.loadPendingRules(mrKey) else emptySet()
        val hasCacheEntry = previousSnapshot.isNotEmpty() && !delta.configChanged && !delta.metadataChanged && pendingRules.isEmpty()
        val reason = cacheReason(previousSnapshot, delta, changedFiles.size, pendingRules.size)
        logger.info("AI plugin: incremental cache for $mrKey -- $reason")
        val headSha = mr.diffRefs?.headSha
        if (lastReviewedSha != null && lastReviewedSha != headSha) {
            logger.info(
                "Incremental: ${changedFiles.size} file(s) changed since last reviewed commit " +
                    "${lastReviewedSha.take(SHORT_SHA)}${headSha?.let { " (head ${it.take(SHORT_SHA)})" }.orEmpty()}",
            )
        }
        return IncrementalContext(
            mrKey = mrKey,
            deltaMr = delta.mr,
            unchangedFiles = delta.unchangedFiles,
            cachedFindings = cachedFindings,
            hasCacheEntry = hasCacheEntry,
            currentSnapshot = currentSnapshot,
            metadataChanged = delta.metadataChanged,
            priorFindingsByChangedFile = priorFindingsByChangedFile,
            priorMrLevelFindings = priorMrLevelFindings,
            lastReviewedSha = lastReviewedSha,
            headSha = headSha,
            configChanged = delta.configChanged,
            fullReviewReason = fullReviewReason,
            pendingRules = pendingRules,
            firstRun = storedSnapshot.isEmpty(),
        )
    }

    /** Why the stored snapshot no longer fits [mr]'s history, or null when it does or the platform gives no history. */
    private fun historyBreak(
        mr: MergeRequest,
        lastReviewedSha: String?,
    ): String? {
        if (lastReviewedSha == null) return "no last reviewed commit stored".takeIf { mr.diffRefs != null }
        // limit: list size stands in for a truncation flag platforms lack; add the flag if long MRs need force-push detection
        val maybeTruncated = mr.commits.size >= TRUNCATED_HISTORY_SIZE
        val kept =
            lastReviewedSha == mr.diffRefs?.headSha ||
                mr.commits.isEmpty() ||
                maybeTruncated ||
                mr.commits.any { it.sha == lastReviewedSha }
        return if (kept) null else "last reviewed commit ${lastReviewedSha.take(SHORT_SHA)} is not in the MR history"
    }

    /**
     * Saves the updated snapshot and caches findings keyed by file path.
     *
     * Findings with a [Location.FileRef] are cached per-file. MR-level findings
     * (without [Location.FileRef]) are cached under the [MR_LEVEL_KEY] synthetic
     * key so they survive incremental runs.
     *
     * @param context The context returned by [prepare] for this run.
     * @param findings All findings produced by the validation pipeline for this run.
     * @param budgetExhausted No AI rule spent a token on changed files. The save is skipped only when a previous
     *   state exists, which then stays as it was for the retry; on a first run the completed rules are still saved.
     * @param incompleteRules AI rules that did not complete this run (provider error, budget, not run). Their
     *   findings are not saved and they are stored as pending, so the next run re-runs only them, on the whole MR.
     * @param skipReason Why no AI rule completed (provider outage, no tokens), named in the log when the save is skipped.
     */
    fun save(
        context: IncrementalContext,
        findings: List<Finding>,
        budgetExhausted: Boolean = false,
        incompleteRules: Set<String> = emptySet(),
        skipReason: String? = null,
    ) {
        if (budgetExhausted && !context.firstRun) {
            logger.debug("Cache save skipped: ${skipReason ?: "no AI rule completed"} — preserving previous cache for retry")
            return
        }
        if (incompleteRules.isNotEmpty()) {
            logger.info("Cache save: ${incompleteRules.sorted().joinToString()} did not complete, next run re-runs them")
        }
        val fresh = findings.filter { it.rule !in incompleteRules }
        // A pending rule that completed now reviewed the whole MR: its fresh findings replace its cached ones.
        val completedPending = context.pendingRules - incompleteRules
        val cached = context.cachedFindings.filter { it.rule !in completedPending }
        // Group fresh findings by file
        val freshFileFindings = fresh
            .mapNotNull { f -> (f.location as? Location.FileRef)?.path?.let { path -> path to f } }
            .groupBy({ it.first }, { it.second })
        val freshMrLevel = fresh.filter { it.location !is Location.FileRef }

        // Merge: cached findings for unchanged files + fresh findings for changed files
        val cachedByFile = cached
            .mapNotNull { f -> (f.location as? Location.FileRef)?.path?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { it in context.unchangedFiles }
        val cachedMrLevel = cached.filter { it.location !is Location.FileRef }

        val mergedFindings = mutableMapOf<String, List<Finding>>()
        mergedFindings.putAll(cachedByFile)
        // Fresh findings of a rule replace that rule's cached ones on the same file; other rules' cached findings stay
        for ((path, freshForFile) in freshFileFindings) {
            val freshRules = freshForFile.mapTo(mutableSetOf()) { it.rule }
            mergedFindings[path] = freshForFile + mergedFindings[path].orEmpty().filter { it.rule !in freshRules }
        }
        // The prompt told the model not to repeat prior findings of changed files, so they stay "already raised"
        if (!context.configChanged) {
            val changesByPath = context.deltaMr.changes.associateBy { it.path }
            for ((path, prior) in context.priorFindingsByChangedFile) {
                val change = changesByPath[path] ?: continue
                val fresh = freshFileFindings[path].orEmpty()
                mergedFindings[path] = fresh + carriedPriors(prior, fresh, change)
            }
            mergedFindings.values.removeAll { it.isEmpty() }
        }
        // Metadata (or config) changed: MR-level findings were re-evaluated, so the fresh set is
        // authoritative even when empty. Otherwise a rule's fresh findings replace its cached ones
        // and cached findings of rules that did not re-run are kept.
        val allMrLevel = if (context.metadataChanged) {
            freshMrLevel
        } else {
            val freshRules = freshMrLevel.map { it.rule }.toSet()
            freshMrLevel + cachedMrLevel.filter { it.rule !in freshRules }
        }
        if (allMrLevel.isNotEmpty()) {
            mergedFindings[MR_LEVEL_KEY] = allMrLevel
        }

        // Remove findings for files deleted from MR
        val currentFiles = context.currentSnapshot.keys
        mergedFindings.keys.retainAll { it == MR_LEVEL_KEY || it in currentFiles }

        persistence.saveSnapshotAndFindings(context.mrKey, context.currentSnapshot, mergedFindings, context.headSha, incompleteRules)

        val totalSaved = mergedFindings.values.sumOf { it.size }
        logger.debug(
            "Cache save: mrKey=${context.mrKey}, snapshot=${context.currentSnapshot.size} file(s), " +
                "findings=$totalSaved total",
        )
        for ((filePath, perFileFindings) in mergedFindings) {
            logger.debug("  $filePath: ${perFileFindings.size} finding(s) saved")
        }
    }

    /**
     * Prior AI findings of a changed file that the fresh run did not re-raise and whose anchor is still there: its
     * `fpv` names an added line of [change], and it moves to that line. Non-AI rules re-evaluate from scratch, and
     * a file-level or `fpv`-less finding cannot be checked, so those are not carried.
     *
     * Limitations: only added lines are matched ([AnchorFingerprint.anchoredLines]), so a finding whose anchor is
     * now a context line is dropped. The check is the anchor text only, so a finding fixed by an edit elsewhere
     * while its anchor line stayed the same is still carried.
     */
    private fun carriedPriors(
        prior: List<Finding>,
        fresh: List<Finding>,
        change: FileChange,
    ): List<Finding> {
        val freshAnchors = fresh.mapNotNullTo(mutableSetOf()) { it.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] }
        val freshSemantic = fresh.mapTo(mutableSetOf()) { FindingFingerprint.generate(it.rule, it.location, it.message) }
        val linesByRule = mutableMapOf<String, Map<String, Int>>()
        return prior.mapNotNull { finding ->
            val loc = finding.location as? Location.FileRef
            val oldLine = loc?.line
            val fpv = finding.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT]
            val carryable = oldLine != null && fpv != null && finding.isAiFinding() && fpv !in freshAnchors &&
                FindingFingerprint.generate(finding.rule, loc, finding.message) !in freshSemantic
            if (!carryable) return@mapNotNull null
            val line = linesByRule.getOrPut(finding.rule) { AnchorFingerprint.anchoredLines(finding.rule, change) }[fpv]
            line?.let { finding.copy(location = loc.copy(line = it, endLine = loc.endLine?.let { end -> end + it - oldLine })) }
        }
    }

    private fun cacheReason(
        previousSnapshot: Map<String, Long>,
        delta: DeltaResult,
        changedCount: Int,
        pendingCount: Int,
    ): String =
        when {
            previousSnapshot.isEmpty() -> "miss (no prior snapshot)"
            delta.configChanged -> "miss (configuration changed since last run)"
            pendingCount > 0 -> "partial ($pendingCount rule(s) did not complete last run; $changedCount file(s) changed)"
            delta.metadataChanged -> "partial (MR metadata changed; $changedCount file(s) changed)"
            changedCount == 0 -> "hit (diff, metadata and configuration unchanged)"
            else -> "partial ($changedCount file(s) changed, ${delta.unchangedFiles.size} unchanged)"
        }

    private fun buildCachedFindings(
        cache: Map<String, List<Finding>>,
        unchangedFiles: List<String>,
        includeMrLevel: Boolean,
    ): List<Finding> {
        if (cache.isEmpty()) return emptyList()
        if (unchangedFiles.isEmpty() && !includeMrLevel) return emptyList()
        val fileFindings = unchangedFiles.flatMap { path -> cache[path] ?: emptyList() }
        val mrLevelFindings = if (includeMrLevel) cache[MR_LEVEL_KEY] ?: emptyList() else emptyList()
        return fileFindings + mrLevelFindings
    }

    companion object {
        /** Synthetic cache key for findings without file location. */
        const val MR_LEVEL_KEY = "__mr-level__"
        private const val SHORT_SHA = 8

        /** GitHub lists at most 250 PR commits, and paginated clients stop at their page cap: a list this long may miss the stored commit. */
        private const val TRUNCATED_HISTORY_SIZE = 250
    }
}

/**
 * Context computed before validation describing the incremental state of the MR.
 *
 * @property mrKey Stable persistence key for this MR.
 * @property deltaMr MR containing only changed or new files relative to the previous run.
 * @property unchangedFiles Paths of files whose diff content is identical to the previous run.
 * @property cachedFindings AI findings cached from the previous run for unchanged files.
 * @property hasCacheEntry True when a valid prior cache entry exists and neither config nor
 *   metadata changed. Used to determine whether a full cache hit is possible.
 * @property currentSnapshot Snapshot of the current MR to persist after this run.
 * @property metadataChanged True when MR-level metadata changed since the previous snapshot,
 *   meaning MR-level findings must be re-evaluated even if file diffs are unchanged.
 * @property priorFindingsByChangedFile Persisted findings keyed by path for files in the current
 *   delta. Surfaced so AI analyzers can show the model what was previously reported for a file
 *   being re-analyzed, reducing duplicated/paraphrased output. Independent of the restore-into-
 *   report path driven by [cachedFindings].
 * @property priorMrLevelFindings Persisted MR-level findings from the previous run. Loaded
 *   regardless of [metadataChanged]: the restore-into-report path uses [cachedFindings] (which
 *   respects the metadata gate); this field is for the AI prompt-context channel only.
 * @property lastReviewedSha Head commit SHA of the previous reviewed run, when known.
 * @property headSha Head commit SHA of this run (from the MR's diff refs), saved as the next run's [lastReviewedSha].
 * @property configChanged True when the config hash changed since the previous snapshot: every cached finding
 *   is stale, so [IncrementalValidator.save] does not carry prior findings forward.
 * @property fullReviewReason Why a stored snapshot was ignored and the MR reviewed in full (a force push or rebase
 *   dropped the last reviewed commit, or none was stored), or null.
 * @property pendingRules AI rules that did not complete in the run that saved the snapshot; they review the whole MR.
 * @property firstRun No state was stored for this MR: every miss of this run is inherent, not a cache failure.
 */
@Serializable
data class IncrementalContext(
    val mrKey: String,
    val deltaMr: MergeRequest,
    val unchangedFiles: List<String>,
    val cachedFindings: List<Finding>,
    val hasCacheEntry: Boolean,
    val currentSnapshot: Map<String, Long>,
    val metadataChanged: Boolean = false,
    val priorFindingsByChangedFile: Map<String, List<Finding>> = emptyMap(),
    val priorMrLevelFindings: List<Finding> = emptyList(),
    val lastReviewedSha: String? = null,
    val headSha: String? = null,
    val configChanged: Boolean = false,
    val fullReviewReason: String? = null,
    val pendingRules: Set<String> = emptySet(),
    val firstRun: Boolean = false,
) {
    /**
     * True when the MR has no changed files and a cache entry exists from a prior run.
     *
     * When this is true the caller may skip fresh validation entirely and serve
     * the [cachedFindings] directly, saving AI token budget. Note that [cachedFindings]
     * may be empty when the previous run produced zero findings - this is still a valid
     * cache hit.
     */
    val isFullyCached: Boolean
        get() = deltaMr.changes.isEmpty() && hasCacheEntry

    /** Paths of files that were changed or added relative to the previous run. */
    val changedFiles: Set<String>
        get() = deltaMr.changes.map { it.path }.toSet()
}
