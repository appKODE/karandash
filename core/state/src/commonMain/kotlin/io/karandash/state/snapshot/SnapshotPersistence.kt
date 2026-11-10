package io.karandash.state.snapshot

/**
 * Provides snapshot load/save operations for [SnapshotManager].
 *
 * Decouples [SnapshotManager] from `AiStatePersistence` (ai-core) so that the manager can be
 * tested with lightweight fakes without touching the filesystem.
 */
interface SnapshotPersistence {
    /**
     * Loads the file-diff snapshot for [mrKey] from the backing store.
     *
     * @return Map of file path to diff content hash, or empty map if no snapshot exists.
     */
    fun loadSnapshot(mrKey: String): Map<String, Long>

    /**
     * Saves [snapshot] for [mrKey] to the backing store.
     *
     * @param mrKey The MR identifier key.
     * @param snapshot Map of file path to diff content hash.
     */
    fun saveSnapshot(
        mrKey: String,
        snapshot: Map<String, Long>
    )

    /**
     * Rules that did not complete in the run that saved [mrKey]'s snapshot (provider error, budget, not run). They
     * review the whole MR on the next run instead of only the files changed since the snapshot. Default: none.
     */
    fun loadPendingRules(mrKey: String): Set<String> = emptySet()
}
