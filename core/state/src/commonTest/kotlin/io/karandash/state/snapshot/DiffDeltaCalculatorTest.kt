package io.karandash.state.snapshot

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffDeltaCalculatorTest {
    private fun makeMr(
        changes: List<FileChange>,
        title: String = "Test",
        description: String? = null,
        commits: List<Commit> = listOf(
            Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z"),
        ),
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
        labels = emptyList(),
        commits = commits,
        changes = changes,
    )

    private fun makeChange(
        path: String,
        diff: String?
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = 1,
        deletions = 0,
        diffContent = diff,
    )

    @Test
    fun `snapshot captures hash of each file diff`() {
        val mr = makeMr(
            listOf(
                makeChange("src/Foo.kt", "diff1"),
                makeChange("src/Bar.kt", "diff2"),
            )
        )
        val snap = DiffDeltaCalculator.snapshot(mr)
        assertEquals(3, snap.size) // 2 files + metadata key
        assertTrue(snap.containsKey("src/Foo.kt"))
        assertTrue(snap.containsKey("src/Bar.kt"))
        assertEquals(io.karandash.crypto.sha256First64Bits("diff1"), snap["src/Foo.kt"])
        assertEquals(io.karandash.crypto.sha256First64Bits("diff2"), snap["src/Bar.kt"])
    }

    @Test
    fun `snapshot uses zero hash for null diff content`() {
        val mr = makeMr(listOf(makeChange("src/Null.kt", null)))
        val snap = DiffDeltaCalculator.snapshot(mr)
        assertEquals(0L, snap["src/Null.kt"])
    }

    @Test
    fun `snapshot includes MR metadata hash`() {
        val mr = makeMr(listOf(makeChange("a.kt", "diff")), title = "My Title", description = "desc")
        val snap = DiffDeltaCalculator.snapshot(mr)
        assertTrue(snap.containsKey(DiffDeltaCalculator.MR_METADATA_KEY))
        assertNotNull(snap[DiffDeltaCalculator.MR_METADATA_KEY])
    }

    @Test
    fun `delta with empty snapshot returns all files`() {
        val mr = makeMr(
            listOf(
                makeChange("a.kt", "diff-a"),
                makeChange("b.kt", "diff-b"),
            )
        )
        val result = DiffDeltaCalculator.delta(mr, emptyMap())
        assertEquals(2, result.mr.changes.size)
        assertTrue(result.unchangedFiles.isEmpty())
        assertTrue(result.hasChanges)
        assertNull(result.summary())
    }

    @Test
    fun `delta filters unchanged files`() {
        val diff = "same diff content"
        val mr = makeMr(listOf(makeChange("src/Same.kt", diff)))
        val snap = DiffDeltaCalculator.snapshot(mr)

        val result = DiffDeltaCalculator.delta(mr, snap)
        assertTrue(result.mr.changes.isEmpty())
        assertEquals(listOf("src/Same.kt"), result.unchangedFiles)
        assertFalse(result.hasChanges)
    }

    @Test
    fun `delta includes new files not in previous snapshot`() {
        val mr1 = makeMr(listOf(makeChange("src/Other.kt", "other")))
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = makeMr(listOf(makeChange("src/New.kt", "new content")))
        val result = DiffDeltaCalculator.delta(mr2, snap)
        assertEquals(1, result.mr.changes.size)
        assertEquals(
            "src/New.kt",
            result.mr.changes
                .first()
                .path
        )
        assertTrue(result.unchangedFiles.isEmpty())
    }

    @Test
    fun `delta includes files with changed diffs`() {
        val mr1 = makeMr(listOf(makeChange("src/Changed.kt", "old diff")))
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = makeMr(listOf(makeChange("src/Changed.kt", "new diff")))
        val result = DiffDeltaCalculator.delta(mr2, snap)
        assertEquals(1, result.mr.changes.size)
        assertEquals(
            "src/Changed.kt",
            result.mr.changes
                .first()
                .path
        )
        assertTrue(result.unchangedFiles.isEmpty())
    }

    @Test
    fun `delta separates changed and unchanged files correctly`() {
        val mr1 = makeMr(
            listOf(
                makeChange("changed.kt", "old diff"),
                makeChange("unchanged.kt", "same diff"),
            )
        )
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = makeMr(
            listOf(
                makeChange("changed.kt", "new diff"),
                makeChange("unchanged.kt", "same diff"),
                makeChange("added.kt", "brand new"),
            )
        )

        val result = DiffDeltaCalculator.delta(mr2, snap)
        assertEquals(2, result.mr.changes.size)
        assertTrue(result.mr.changes.any { it.path == "changed.kt" })
        assertTrue(result.mr.changes.any { it.path == "added.kt" })
        assertEquals(listOf("unchanged.kt"), result.unchangedFiles)
        assertTrue(result.hasChanges)
    }

    @Test
    fun `delta result summary describes filtered files`() {
        val result = DeltaResult(
            mr = makeMr(emptyList()),
            unchangedFiles = listOf("a.kt", "b.kt"),
        )
        val summary = result.summary()
        assertNotNull(summary)
        assertTrue(summary.contains("2 unchanged files skipped"))
        assertTrue(summary.contains("a.kt"))
        assertTrue(summary.contains("b.kt"))
    }

    @Test
    fun `delta result hasChanges is false when all files unchanged`() {
        val diff = "the diff"
        val mr = makeMr(listOf(makeChange("src/Foo.kt", diff)))
        val snap = DiffDeltaCalculator.snapshot(mr)

        val result = DiffDeltaCalculator.delta(mr, snap)
        assertFalse(result.hasChanges)
        assertEquals(1, result.unchangedFiles.size)
    }

    @Test
    fun `delta detects metadata change but preserves file-level unchanged files`() {
        val mr1 = makeMr(
            listOf(makeChange("src/Foo.kt", "diff")),
            title = "Original title",
        )
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = makeMr(
            listOf(makeChange("src/Foo.kt", "diff")),
            title = "Changed title",
        )
        val result = DiffDeltaCalculator.delta(mr2, snap)
        // File diff is unchanged - file goes to unchangedFiles, not changed
        assertTrue(result.mr.changes.isEmpty())
        assertEquals(listOf("src/Foo.kt"), result.unchangedFiles)
        assertFalse(result.hasChanges)
        assertTrue(result.metadataChanged)
        assertFalse(result.configChanged)
    }

    @Test
    fun `delta detects description change but preserves unchanged file findings`() {
        val mr1 = makeMr(
            listOf(makeChange("src/Foo.kt", "diff")),
            description = "Original description",
        )
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = makeMr(
            listOf(makeChange("src/Foo.kt", "diff")),
            description = "Updated description",
        )
        val result = DiffDeltaCalculator.delta(mr2, snap)
        // File diff unchanged - only MR-level metadata changed
        assertFalse(result.hasChanges)
        assertEquals(listOf("src/Foo.kt"), result.unchangedFiles)
        assertTrue(result.metadataChanged)
    }

    @Test
    fun `delta ignores synthetic metadata key in file comparison`() {
        val mr = makeMr(listOf(makeChange("src/Foo.kt", "diff")))
        val snap = DiffDeltaCalculator.snapshot(mr)

        val result = DiffDeltaCalculator.delta(mr, snap)
        assertFalse(result.hasChanges)
        assertFalse(result.mr.changes.any { it.path == DiffDeltaCalculator.MR_METADATA_KEY })
    }

    @Test
    fun `delta detects label change but preserves unchanged file findings`() {
        val mr1 = makeMr(listOf(makeChange("src/Foo.kt", "diff")))
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = mr1.copy(labels = listOf("bug"))
        val result = DiffDeltaCalculator.delta(mr2, snap)
        assertFalse(result.hasChanges)
        assertEquals(listOf("src/Foo.kt"), result.unchangedFiles)
        assertTrue(result.metadataChanged)
    }

    @Test
    fun `delta detects draft status change but preserves unchanged file findings`() {
        val mr1 = makeMr(listOf(makeChange("src/Foo.kt", "diff")))
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = mr1.copy(draft = true)
        val result = DiffDeltaCalculator.delta(mr2, snap)
        assertFalse(result.hasChanges)
        assertEquals(listOf("src/Foo.kt"), result.unchangedFiles)
        assertTrue(result.metadataChanged)
    }

    @Test
    fun `delta detects state change but preserves unchanged file findings`() {
        val mr1 = makeMr(listOf(makeChange("src/Foo.kt", "diff")))
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = mr1.copy(state = MergeRequestState.MERGED)
        val result = DiffDeltaCalculator.delta(mr2, snap)
        assertFalse(result.hasChanges)
        assertEquals(listOf("src/Foo.kt"), result.unchangedFiles)
        assertTrue(result.metadataChanged)
    }

    @Test
    fun `snapshot metadata hash includes labels in sorted order`() {
        val mr1 = makeMr(
            listOf(makeChange("a.kt", "diff")),
        ).copy(labels = listOf("b", "a"))
        val mr2 = makeMr(
            listOf(makeChange("a.kt", "diff")),
        ).copy(labels = listOf("a", "b"))
        val snap1 = DiffDeltaCalculator.snapshot(mr1)
        val snap2 = DiffDeltaCalculator.snapshot(mr2)
        assertEquals(
            snap1[DiffDeltaCalculator.MR_METADATA_KEY],
            snap2[DiffDeltaCalculator.MR_METADATA_KEY],
        )
    }

    @Test
    fun `metadataChangeDoesNotInvalidateFileFindings`() {
        val mr1 = makeMr(
            listOf(
                makeChange("a.kt", "diff-a"),
                makeChange("b.kt", "diff-b"),
            ),
            title = "Original",
        )
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = mr1.copy(title = "Updated title")
        val result = DiffDeltaCalculator.delta(mr2, snap)

        assertEquals(listOf("a.kt", "b.kt"), result.unchangedFiles.sorted())
        assertTrue(result.metadataChanged)
        assertFalse(result.configChanged)
        assertFalse(result.hasChanges)
    }

    @Test
    fun `configChangeInvalidatesEverything`() {
        val mr = makeMr(
            listOf(
                makeChange("a.kt", "diff-a"),
                makeChange("b.kt", "diff-b"),
            ),
        )
        val snap = DiffDeltaCalculator.snapshot(mr, configHash = 111L)

        val result = DiffDeltaCalculator.delta(mr, snap, currentConfigHash = 999L)

        assertTrue(result.unchangedFiles.isEmpty())
        assertTrue(result.configChanged)
        assertTrue(result.metadataChanged)
        assertTrue(result.hasChanges)
    }

    @Test
    fun `fileChangeWithMetadataUnchanged`() {
        val mr1 = makeMr(
            listOf(
                makeChange("a.kt", "original"),
                makeChange("b.kt", "same"),
            ),
        )
        val snap = DiffDeltaCalculator.snapshot(mr1)

        val mr2 = makeMr(
            listOf(
                makeChange("a.kt", "modified"),
                makeChange("b.kt", "same"),
            ),
        )
        val result = DiffDeltaCalculator.delta(mr2, snap)

        assertEquals(listOf("b.kt"), result.unchangedFiles)
        assertEquals(1, result.mr.changes.size)
        assertEquals(
            "a.kt",
            result.mr.changes
                .first()
                .path
        )
        assertFalse(result.metadataChanged)
        assertFalse(result.configChanged)
    }

    @Test
    fun `configHashNullBackwardsCompatible`() {
        // Old snapshot without CONFIG_KEY; new delta provides a configHash - treated as changed (safe fallback)
        val mr = makeMr(listOf(makeChange("a.kt", "diff")))
        // snapshot() called without configHash → no CONFIG_KEY stored
        val snap = DiffDeltaCalculator.snapshot(mr)
        assertFalse(snap.containsKey(DiffDeltaCalculator.CONFIG_KEY))

        // Delta with a currentConfigHash provided - previousConfigHash is null → configChanged
        val result = DiffDeltaCalculator.delta(mr, snap, currentConfigHash = 42L)
        assertTrue(result.configChanged)
        assertTrue(result.unchangedFiles.isEmpty())
    }

    @Test
    fun `snapshot stores configHash under CONFIG_KEY when provided`() {
        val mr = makeMr(listOf(makeChange("a.kt", "diff")))
        val snap = DiffDeltaCalculator.snapshot(mr, configHash = 12345L)
        assertEquals(12345L, snap[DiffDeltaCalculator.CONFIG_KEY])
    }

    @Test
    fun `snapshot without configHash does not include CONFIG_KEY`() {
        val mr = makeMr(listOf(makeChange("a.kt", "diff")))
        val snap = DiffDeltaCalculator.snapshot(mr)
        assertFalse(snap.containsKey(DiffDeltaCalculator.CONFIG_KEY))
    }

    @Test
    fun `delta with empty snapshot sets both metadataChanged and configChanged`() {
        val mr = makeMr(listOf(makeChange("a.kt", "diff")))
        val result = DiffDeltaCalculator.delta(mr, emptyMap(), currentConfigHash = 1L)
        assertTrue(result.metadataChanged)
        assertTrue(result.configChanged)
        assertTrue(result.hasChanges)
        assertTrue(result.unchangedFiles.isEmpty())
    }
}
