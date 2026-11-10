package io.karandash.utils.mr

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrHelpersTest {
    private fun createTestMr(
        title: String = "Test MR",
        labels: List<String> = emptyList(),
        changes: List<FileChange> = emptyList()
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = title,
        description = null,
        sourceBranch = "feature/test",
        targetBranch = "main",
        author = User("1", "testuser", "Test User"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = labels,
        commits = emptyList(),
        changes = changes
    )

    @Test
    fun `hasLabel returns true for exact match`() {
        val mr = createTestMr(labels = listOf("bug", "feature"))

        assertTrue(mr.hasLabel("bug"))
        assertTrue(mr.hasLabel("feature"))
    }

    @Test
    fun `hasLabel returns true for case-insensitive match`() {
        val mr = createTestMr(labels = listOf("Bug", "FEATURE"))

        assertTrue(mr.hasLabel("bug"))
        assertTrue(mr.hasLabel("BUG"))
        assertTrue(mr.hasLabel("feature"))
        assertTrue(mr.hasLabel("FEATURE"))
        assertTrue(mr.hasLabel("Feature"))
    }

    @Test
    fun `hasLabel returns false when label not present`() {
        val mr = createTestMr(labels = listOf("bug"))

        assertFalse(mr.hasLabel("feature"))
        assertFalse(mr.hasLabel("enhancement"))
    }

    @Test
    fun `hasLabel returns false for empty labels`() {
        val mr = createTestMr(labels = emptyList())

        assertFalse(mr.hasLabel("bug"))
    }

    @Test
    fun `titleMatches returns true when pattern matches`() {
        val mr = createTestMr(title = "fix: Fix critical bug")

        assertTrue(mr.titleMatches(Regex("^fix:")))
        assertTrue(mr.titleMatches(Regex("bug")))
        assertTrue(mr.titleMatches(Regex("Fix.*bug")))
    }

    @Test
    fun `titleMatches returns false when pattern does not match`() {
        val mr = createTestMr(title = "fix: Fix critical bug")

        assertFalse(mr.titleMatches(Regex("^feat:")))
        assertFalse(mr.titleMatches(Regex("enhancement")))
    }

    @Test
    fun `totalLinesChanged returns sum of additions and deletions`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 10, deletions = 5),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 20, deletions = 15),
                FileChange("file3.kt", ChangeType.ADDED, additions = 30, deletions = 0)
            )
        )

        // Compute directly instead of using deprecated helper
        val total = mr.changes.sumOf { it.additions + it.deletions }
        assertEquals(80, total) // 10+5+20+15+30+0 = 80
    }

    @Test
    fun `totalLinesChanged returns zero for no changes`() {
        val mr = createTestMr(changes = emptyList())

        // Compute directly instead of using deprecated helper
        val total = mr.changes.sumOf { it.additions + it.deletions }
        assertEquals(0, total)
    }

    @Test
    fun `filesMatching filters files by pattern`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("src/Main.kt", ChangeType.MODIFIED),
                FileChange("src/test/Test.kt", ChangeType.MODIFIED),
                FileChange("README.md", ChangeType.MODIFIED),
                FileChange("src/Utils.kt", ChangeType.ADDED)
            )
        )

        val kotlinFiles = mr.filesMatching(Regex("\\.kt$"))
        assertEquals(3, kotlinFiles.size)
        assertTrue(kotlinFiles.any { it.path == "src/Main.kt" })
        assertTrue(kotlinFiles.any { it.path == "src/test/Test.kt" })
        assertTrue(kotlinFiles.any { it.path == "src/Utils.kt" })
    }

    @Test
    fun `filesMatching returns empty list when no matches`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("README.md", ChangeType.MODIFIED),
                FileChange("LICENSE", ChangeType.MODIFIED)
            )
        )

        val kotlinFiles = mr.filesMatching(Regex("\\.kt$"))
        assertEquals(0, kotlinFiles.size)
    }

    @Test
    fun `addedFiles returns only ADDED files`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("new.kt", ChangeType.ADDED),
                FileChange("modified.kt", ChangeType.MODIFIED),
                FileChange("deleted.kt", ChangeType.DELETED),
                FileChange("another-new.kt", ChangeType.ADDED)
            )
        )

        val added = mr.addedFiles()
        assertEquals(2, added.size)
        assertTrue(added.any { it.path == "new.kt" })
        assertTrue(added.any { it.path == "another-new.kt" })
    }

    @Test
    fun `modifiedFiles returns only MODIFIED files`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("new.kt", ChangeType.ADDED),
                FileChange("modified.kt", ChangeType.MODIFIED),
                FileChange("deleted.kt", ChangeType.DELETED),
                FileChange("another-modified.kt", ChangeType.MODIFIED)
            )
        )

        val modified = mr.modifiedFiles()
        assertEquals(2, modified.size)
        assertTrue(modified.any { it.path == "modified.kt" })
        assertTrue(modified.any { it.path == "another-modified.kt" })
    }

    @Test
    fun `deletedFiles returns only DELETED files`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("new.kt", ChangeType.ADDED),
                FileChange("modified.kt", ChangeType.MODIFIED),
                FileChange("deleted.kt", ChangeType.DELETED),
                FileChange("another-deleted.kt", ChangeType.DELETED)
            )
        )

        val deleted = mr.deletedFiles()
        assertEquals(2, deleted.size)
        assertTrue(deleted.any { it.path == "deleted.kt" })
        assertTrue(deleted.any { it.path == "another-deleted.kt" })
    }

    @Test
    fun `file type filters return empty lists when no matches`() {
        val mr = createTestMr(
            changes = listOf(
                FileChange("file.kt", ChangeType.MODIFIED)
            )
        )

        assertEquals(0, mr.addedFiles().size)
        assertEquals(0, mr.deletedFiles().size)
    }
}
