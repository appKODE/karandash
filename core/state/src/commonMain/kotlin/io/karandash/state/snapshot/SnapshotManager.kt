package io.karandash.state.snapshot

import io.karandash.models.MergeRequest
import io.karandash.models.MrCacheKey
import io.karandash.sdk.Logger
import io.karandash.utils.DurationFormatter
import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable
import kotlin.time.measureTimedValue

/**
 * Manages per-run snapshot loading, delta checking, and snapshot persistence for AI rule validation.
 *
 * A single instance is created per MR validation run and shared across all rule invocations.
 * This provides:
 * - Snapshot loaded from disk exactly once per run, with timing diagnostics.
 * - Within-run snapshot writes never trigger delta skips for sibling rules, preventing
 *   contamination when multiple rules run in the same validation cycle.
 * - Rich [DeltaCheckResult] carrying source, load timing, and filtered-file metadata.
 *
 * Usage pattern:
 * ```
 * val manager = SnapshotManager(persistence, mrKey, logger)
 * manager.load()                              // call once, before first rule check
 * val result = manager.checkDelta(ruleId, mr) // call per rule
 * manager.saveRunSnapshot(mr)                 // call after first successful rule execution
 * ```
 */
class SnapshotManager(
    private val persistence: SnapshotPersistence?,
    private val mrKey: String,
    private val logger: Logger,
    /** Effective-config hash for this run; a mismatch with the persisted one forces a re-run. */
    private val configHash: Long? = null,
) {
    // Benign race: if two rules enter load() before `loaded` is set, both load the same
    // snapshot from disk - the result is identical and idempotent.
    private val loaded = atomic(false)
    private val loadResult = atomic(
        SnapshotLoadResult(snapshot = emptyMap(), source = SnapshotSource.NONE, loadDurationMs = 0L),
    )

    // Rules that did not complete when the snapshot was saved: they ignore it and review the whole MR.
    private val pendingRules = atomic(emptySet<String>())

    // Prevents duplicate disk writes when multiple rules call saveRunSnapshot().
    private val runSnapshotSaved = atomic(false)

    /**
     * Loads the persisted snapshot from the backing store.
     *
     * Must be called once before the first [checkDelta] call. Subsequent calls are no-ops.
     * Load timing is captured and surfaced through [DeltaCheckResult].
     */
    fun load() {
        if (loaded.value) return
        val p = persistence
        if (p == null) {
            loaded.value = true
            return
        }
        val (snapshot, duration) = measureTimedValue { p.loadSnapshot(mrKey) }
        pendingRules.value = if (snapshot.isNotEmpty()) p.loadPendingRules(mrKey) else emptySet()
        val durationMs = duration.inWholeMilliseconds
        val result = if (snapshot.isNotEmpty()) {
            logger.debug(
                "AI plugin: snapshot loaded for $mrKey — ${snapshot.size} file(s), ${DurationFormatter.formatMs(durationMs)}"
            )
            SnapshotLoadResult(snapshot = snapshot, source = SnapshotSource.PERSISTENCE, loadDurationMs = durationMs)
        } else {
            logger.debug("AI plugin: no prior snapshot for $mrKey (${DurationFormatter.formatMs(durationMs)})")
            SnapshotLoadResult(snapshot = emptyMap(), source = SnapshotSource.NONE, loadDurationMs = durationMs)
        }
        loadResult.value = result
        loaded.value = true
    }

    /**
     * Checks whether [ruleId] should be skipped because its input is unchanged since the last persisted review.
     *
     * Skip is only triggered when a snapshot from a **previous run** exists ([SnapshotSource.PERSISTENCE])
     * AND neither the file diffs nor the MR-level metadata (title, description, commits, labels, draft, state)
     * have changed. A metadata-only change forces rules such as `mr-summary` and `risk-scoring` to re-run against
     * the current title/description; their previously cached MR-level findings are intentionally invalidated
     * upstream by `IncrementalValidator` (ai-core).
     *
     * Within-run snapshots (written by [saveRunSnapshot]) are never used for skip decisions,
     * preventing sibling-rule contamination when multiple rules run in the same validation cycle.
     *
     * @param ruleId Rule identifier used for diagnostic log messages.
     * @param mr The file-filtered merge request for this rule.
     * @return [DeltaCheckResult.Skip] if no file changes AND no MR-metadata changes are detected against the
     *         persisted snapshot, [DeltaCheckResult.Proceed] otherwise (including when no prior snapshot exists).
     */
    fun checkDelta(
        ruleId: String,
        mr: MergeRequest
    ): DeltaCheckResult {
        val current = loadResult.value.let { if (ruleId in pendingRules.value) it.copy(snapshot = emptyMap()) else it }
        if (ruleId in pendingRules.value) logger.debug("AI plugin [$ruleId]: did not complete last run, reviewing the whole MR")
        val deltaResult = DiffDeltaCalculator.delta(mr, current.snapshot, configHash)
        val shouldSkip = !deltaResult.hasChanges &&
            !deltaResult.metadataChanged &&
            current.source == SnapshotSource.PERSISTENCE

        return if (shouldSkip) {
            val reason = buildSkipReason(current, deltaResult.unchangedFiles.size)
            logger.debug("AI plugin [$ruleId]: delta skip — $reason")
            DeltaCheckResult.Skip(
                reason = reason,
                snapshotSource = current.source,
                snapshotLoadDurationMs = current.loadDurationMs,
            )
        } else {
            if (deltaResult.unchangedFiles.isNotEmpty()) {
                logger.debug(
                    "AI plugin [$ruleId]: ${deltaResult.unchangedFiles.size} unchanged file(s) filtered, " +
                        "${deltaResult.mr.changes.size} file(s) to review " +
                        "(snapshot: ${current.source}, ${DurationFormatter.formatMs(current.loadDurationMs)})"
                )
            }
            DeltaCheckResult.Proceed(
                filteredMr = deltaResult.mr,
                unchangedFiles = deltaResult.unchangedFiles,
                snapshotSource = current.source,
                snapshotLoadDurationMs = current.loadDurationMs,
            )
        }
    }

    /**
     * Records the current MR diff state as a snapshot for future incremental runs.
     *
     * Idempotent - only writes to disk once per [SnapshotManager] instance regardless of how
     * many rules call it. Should be called after a rule completes successfully.
     *
     * Not called by the AI rule pipeline: the persisted snapshot is written only by
     * `IncrementalValidator.save` (ai-core) (via
     * `AiPipelineExtension.saveIncrementalState`) when incremental review is enabled,
     * so there is no double write to suppress. Calling this from the rule path would turn on delta skips for
     * users who disabled incremental review.
     */
    fun saveRunSnapshot(mr: MergeRequest) {
        if (!runSnapshotSaved.compareAndSet(expect = false, update = true)) return
        val snapshot = DiffDeltaCalculator.snapshot(mr, configHash)
        persistence?.saveSnapshot(mrKey, snapshot)
        logger.debug("AI plugin: snapshot saved for $mrKey — ${snapshot.size} file(s)")
    }

    private fun buildSkipReason(
        load: SnapshotLoadResult,
        unchangedCount: Int
    ): String =
        "no changes since last review (snapshot: ${load.source}, " +
            "loaded in ${DurationFormatter.formatMs(load.loadDurationMs)}, $unchangedCount unchanged file(s))"

    companion object {
        /**
         * Stable, sanitized key for [mr], used as the state directory name ([MrCacheKey.buildKey]); the rule path and
         * `IncrementalValidator` (ai-core) key their snapshots the same way.
         */
        fun mrKey(mr: MergeRequest): String = MrCacheKey.buildKey(mr)
    }
}

/**
 * Outcome of loading a persisted snapshot for an MR.
 *
 * @property snapshot Map of file path to diff content hash; empty when [source] is [SnapshotSource.NONE].
 * @property source Where the snapshot was loaded from.
 * @property loadDurationMs Wall-clock time to load from disk (0 if no persistence is configured).
 */
@Serializable
data class SnapshotLoadResult(
    val snapshot: Map<String, Long>,
    val source: SnapshotSource,
    val loadDurationMs: Long,
)

/** Indicates where a snapshot was loaded from. */
enum class SnapshotSource {
    /** Snapshot was loaded from state persisted by a previous run. */
    PERSISTENCE,

    /** No persisted snapshot exists for this MR (first-time review or persistence not configured). */
    NONE,
}

/**
 * Result of a per-rule delta check performed by [SnapshotManager.checkDelta].
 */
sealed class DeltaCheckResult {
    /**
     * The rule should be skipped - no file changes detected since the last persisted review.
     *
     * @property reason Human-readable explanation including snapshot source, load timing, and file count.
     * @property snapshotSource The source that triggered the skip.
     * @property snapshotLoadDurationMs Time taken to load the snapshot from disk.
     */
    @Serializable
    data class Skip(
        val reason: String,
        val snapshotSource: SnapshotSource,
        val snapshotLoadDurationMs: Long,
    ) : DeltaCheckResult()

    /**
     * The rule should proceed with AI analysis.
     *
     * @property filteredMr MR containing only files that changed since the last review.
     *                       Equals the input MR when no prior snapshot exists.
     * @property unchangedFiles Paths of files filtered out because their diffs are unchanged.
     * @property snapshotSource Where the delta baseline snapshot came from.
     * @property snapshotLoadDurationMs Time taken to load the snapshot from disk.
     */
    @Serializable
    data class Proceed(
        val filteredMr: MergeRequest,
        val unchangedFiles: List<String>,
        val snapshotSource: SnapshotSource,
        val snapshotLoadDurationMs: Long,
    ) : DeltaCheckResult()
}
