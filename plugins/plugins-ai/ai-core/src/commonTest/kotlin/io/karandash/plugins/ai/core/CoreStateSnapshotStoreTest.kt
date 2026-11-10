package io.karandash.plugins.ai.core

import io.karandash.cache.cas.CasCache
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import io.karandash.state.snapshot.DeltaCheckResult as StateDeltaCheckResult
import io.karandash.state.snapshot.SnapshotManager as StateSnapshotManager

/**
 * the snapshot store (manager, persistence port, delta calculator) lives in core/state; the ai-core
 * names are typealiases of it, and the AI state persistence still backs it across runs.
 */
class CoreStateSnapshotStoreTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-core-state-snapshot")

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(dir)
    }

    @Test
    fun `ai-core snapshot manager is the core state one and skips an unchanged MR`() {
        val persistence = AiStatePersistence(CasCache(baseDir = dir, namespace = "state"))
        val mr = createTestMergeRequest()
        val key = SnapshotManager.mrKey(mr)
        persistence.saveSnapshot(key, DiffDeltaCalculator.snapshot(mr))

        val manager: StateSnapshotManager = SnapshotManager(persistence, key, NoOpLogger)
        manager.load()

        assertIs<StateDeltaCheckResult.Skip>(manager.checkDelta("review-comments", mr))
    }
}
