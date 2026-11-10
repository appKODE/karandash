package io.karandash.state.snapshot

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.MrCacheKey
import io.karandash.models.User
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnapshotManagerTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private class FakePersistence(
        initialSnapshot: Map<String, Long> = emptyMap(),
        private val pending: Set<String> = emptySet(),
    ) : SnapshotPersistence {
        var loadCallCount: Int = 0
        var savedSnapshot: Map<String, Long>? = null
        private val stored: MutableMap<String, Map<String, Long>> = mutableMapOf()

        init {
            if (initialSnapshot.isNotEmpty()) stored["mr-1"] = initialSnapshot
        }

        override fun loadSnapshot(mrKey: String): Map<String, Long> {
            loadCallCount++
            return stored[mrKey] ?: emptyMap()
        }

        override fun saveSnapshot(
            mrKey: String,
            snapshot: Map<String, Long>
        ) {
            savedSnapshot = snapshot
            stored[mrKey] = snapshot
        }

        override fun loadPendingRules(mrKey: String): Set<String> = pending
    }

    private fun makeMr(
        vararg paths: Pair<String, String?>,
        title: String = "Test",
        description: String? = null,
        labels: List<String> = emptyList(),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = title,
        description = description,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = labels,
        commits = listOf(Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")),
        changes = paths.map { (path, diff) ->
            FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff)
        },
    )

    // --- load() ---

    @Test
    fun `load with null persistence sets NONE source`() {
        val manager = SnapshotManager(null, "mr-1", noopLogger)
        manager.load()
        val result = manager.checkDelta("rule", makeMr("a.kt" to "diff"))
        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(SnapshotSource.NONE, result.snapshotSource)
    }

    @Test
    fun `load with empty stored snapshot sets NONE source`() {
        val persistence = FakePersistence()
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        assertEquals(1, persistence.loadCallCount)
        val result = manager.checkDelta("rule", makeMr("a.kt" to "diff"))
        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(SnapshotSource.NONE, result.snapshotSource)
    }

    @Test
    fun `load with existing snapshot sets PERSISTENCE source`() {
        val mr = makeMr("a.kt" to "diff")
        val snap = DiffDeltaCalculator.snapshot(mr)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        assertEquals(1, persistence.loadCallCount)
        val result = manager.checkDelta("rule", mr)
        assertIs<DeltaCheckResult.Skip>(result)
        assertEquals(SnapshotSource.PERSISTENCE, result.snapshotSource)
    }

    @Test
    fun `changed config hash on unchanged diff proceeds instead of delta skip`() {
        val mr = makeMr("a.kt" to "diff")
        val persistence = FakePersistence(DiffDeltaCalculator.snapshot(mr, configHash = 1L))
        val manager = SnapshotManager(persistence, "mr-1", noopLogger, configHash = 2L)
        manager.load()
        assertIs<DeltaCheckResult.Proceed>(manager.checkDelta("rule", mr))
    }

    @Test
    fun `same config hash on unchanged diff is delta skipped`() {
        val mr = makeMr("a.kt" to "diff")
        val persistence = FakePersistence(DiffDeltaCalculator.snapshot(mr, configHash = 1L))
        val manager = SnapshotManager(persistence, "mr-1", noopLogger, configHash = 1L)
        manager.load()
        assertIs<DeltaCheckResult.Skip>(manager.checkDelta("rule", mr))
    }

    @Test
    fun `rule that did not complete last run reviews the whole MR while others delta skip`() {
        val mr = makeMr("a.kt" to "diff-a", "b.kt" to "diff-b")
        val persistence = FakePersistence(DiffDeltaCalculator.snapshot(mr), pending = setOf("failed"))
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        assertIs<DeltaCheckResult.Skip>(manager.checkDelta("done", mr))
        val pending = manager.checkDelta("failed", mr)
        assertIs<DeltaCheckResult.Proceed>(pending)
        assertEquals(2, pending.filteredMr.changes.size)
    }

    @Test
    fun `load called twice only hits persistence once`() {
        val persistence = FakePersistence()
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        manager.load()
        assertEquals(1, persistence.loadCallCount)
    }

    // --- checkDelta() ---

    @Test
    fun `checkDelta with no prior snapshot returns Proceed with all files`() {
        val manager = SnapshotManager(null, "mr-1", noopLogger)
        manager.load()
        val mr = makeMr("a.kt" to "diff-a", "b.kt" to "diff-b")
        val result = manager.checkDelta("rule", mr)
        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(2, result.filteredMr.changes.size)
        assertTrue(result.unchangedFiles.isEmpty())
    }

    @Test
    fun `checkDelta with PERSISTENCE source and unchanged files returns Skip`() {
        val diff = "same content"
        val mr = makeMr("src/Foo.kt" to diff)
        val snap = DiffDeltaCalculator.snapshot(mr)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        val result = manager.checkDelta("ai-review", mr)
        assertIs<DeltaCheckResult.Skip>(result)
        assertEquals(SnapshotSource.PERSISTENCE, result.snapshotSource)
        assertTrue(result.reason.contains("no changes since last review"))
        assertTrue(result.reason.contains("PERSISTENCE"))
        assertTrue(result.reason.contains("1 unchanged file(s)"))
    }

    @Test
    fun `checkDelta with PERSISTENCE source and changed files returns Proceed`() {
        val mrOld = makeMr("src/Foo.kt" to "old diff")
        val snap = DiffDeltaCalculator.snapshot(mrOld)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        val result = manager.checkDelta("ai-review", makeMr("src/Foo.kt" to "new diff"))
        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(1, result.filteredMr.changes.size)
        assertTrue(result.unchangedFiles.isEmpty())
    }

    @Test
    fun `checkDelta with PERSISTENCE source and partial changes skips unchanged filters changed`() {
        val mrOld = makeMr("unchanged.kt" to "same", "changed.kt" to "old")
        val snap = DiffDeltaCalculator.snapshot(mrOld)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        val mr = makeMr("unchanged.kt" to "same", "changed.kt" to "new")
        val result = manager.checkDelta("rule", mr)
        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(1, result.filteredMr.changes.size)
        assertEquals(
            "changed.kt",
            result.filteredMr.changes
                .first()
                .path
        )
        assertEquals(listOf("unchanged.kt"), result.unchangedFiles)
    }

    @Test
    fun `checkDelta with NONE source does not skip even when files match within-run snapshot`() {
        // No persistence - simulate within-run scenario where snapshotManager has NONE source
        val manager = SnapshotManager(null, "mr-1", noopLogger)
        manager.load()
        val mr = makeMr("a.kt" to "diff")
        // First rule saves the snapshot
        manager.saveRunSnapshot(mr)
        // Second rule checks delta - must NOT skip even though files match, because source is NONE
        val result = manager.checkDelta("rule-2", mr)
        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(SnapshotSource.NONE, result.snapshotSource)
    }

    @Test
    fun `checkDelta with PERSISTENCE source and metadata-only change returns Proceed`() {
        // A title change with identical diffs must not skip every AI rule: stale MR-level findings are not reused.
        val diff = "same diff content"
        val mrOld = makeMr("src/Foo.kt" to diff, title = "Old title")
        val snap = DiffDeltaCalculator.snapshot(mrOld)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()

        val mrNew = makeMr("src/Foo.kt" to diff, title = "New title")
        val result = manager.checkDelta("mr-summary", mrNew)

        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(SnapshotSource.PERSISTENCE, result.snapshotSource)
        assertTrue(result.filteredMr.changes.isEmpty())
        assertEquals(listOf("src/Foo.kt"), result.unchangedFiles)
    }

    @Test
    fun `checkDelta with PERSISTENCE source proceeds when labels change`() {
        val diff = "same diff content"
        val mrOld = makeMr("src/Foo.kt" to diff, labels = listOf("bug"))
        val snap = DiffDeltaCalculator.snapshot(mrOld)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()

        val mrNew = makeMr("src/Foo.kt" to diff, labels = listOf("bug", "urgent"))
        val result = manager.checkDelta("risk-scoring", mrNew)

        assertIs<DeltaCheckResult.Proceed>(result)
        assertEquals(SnapshotSource.PERSISTENCE, result.snapshotSource)
    }

    @Test
    fun `checkDelta with PERSISTENCE source proceeds when description changes`() {
        val diff = "same diff content"
        val mrOld = makeMr("src/Foo.kt" to diff, description = "Initial description")
        val snap = DiffDeltaCalculator.snapshot(mrOld)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()

        val mrNew = makeMr("src/Foo.kt" to diff, description = "Updated description")
        val result = manager.checkDelta("comment-analysis", mrNew)

        assertIs<DeltaCheckResult.Proceed>(result)
    }

    @Test
    fun `checkDelta Skip reason includes load timing and file count`() {
        val diff = "content"
        val mr = makeMr("file.kt" to diff)
        val snap = DiffDeltaCalculator.snapshot(mr)
        val persistence = FakePersistence(snap)
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        val result = manager.checkDelta("rule", mr)
        assertIs<DeltaCheckResult.Skip>(result)
        // reason should contain the load timing (DurationFormatter renders 0 as "0s", so don't assume "ms") and count
        assertTrue(result.reason.contains("loaded in "))
        assertTrue(result.reason.contains("unchanged file"))
    }

    // --- saveRunSnapshot() ---

    @Test
    fun `saveRunSnapshot saves snapshot to persistence`() {
        val persistence = FakePersistence()
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        val mr = makeMr("a.kt" to "diff-a")
        manager.saveRunSnapshot(mr)
        val expected = DiffDeltaCalculator.snapshot(mr)
        assertEquals(expected, persistence.savedSnapshot)
    }

    @Test
    fun `saveRunSnapshot is idempotent, only writes once`() {
        var saveCount = 0
        val persistence = object : SnapshotPersistence {
            override fun loadSnapshot(mrKey: String) = emptyMap<String, Long>()

            override fun saveSnapshot(
                mrKey: String,
                snapshot: Map<String, Long>
            ) {
                saveCount++
            }
        }
        val manager = SnapshotManager(persistence, "mr-1", noopLogger)
        manager.load()
        val mr = makeMr("a.kt" to "diff")
        manager.saveRunSnapshot(mr)
        manager.saveRunSnapshot(mr)
        manager.saveRunSnapshot(mr)
        assertEquals(1, saveCount)
        assertEquals(emptySet(), persistence.loadPendingRules("mr-1"))
        assertEquals(MrCacheKey.buildKey(mr), SnapshotManager.mrKey(mr))
    }

    @Test
    fun `saveRunSnapshot with null persistence does not throw`() {
        val manager = SnapshotManager(null, "mr-1", noopLogger)
        manager.load()
        manager.saveRunSnapshot(makeMr("a.kt" to "diff")) // should not throw
    }
}
