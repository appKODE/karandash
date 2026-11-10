package io.karandash.utils.validation

import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.ReviewState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrValidationContextTest {
    private fun minimalMr(
        reviewers: List<User> = emptyList(),
        approvals: List<Approval> = emptyList(),
        labels: List<String> = emptyList(),
        changes: List<FileChange> = emptyList(),
        draft: Boolean = false
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "fix: minimal",
        description = null,
        sourceBranch = "fix/branch",
        targetBranch = "main",
        author = User("author", "Author"),
        state = MergeRequestState.OPENED,
        draft = draft,
        labels = labels,
        commits = emptyList(),
        changes = changes,
        approvals = approvals,
        reviewers = reviewers
    )

    // ── reviewerNames ────────────────────────────────────────────────────────

    @Test
    fun `reviewerNames returns empty set when MR has no reviewers`() {
        val ctx = minimalMr().toContext()
        assertEquals(emptySet(), ctx.reviewerNames)
    }

    @Test
    fun `reviewerNames returns usernames of all reviewers`() {
        val mr = minimalMr(
            reviewers = listOf(
                User("alice", "Alice"),
                User("bob", "Bob")
            )
        )
        val ctx = mr.toContext()
        assertEquals(setOf("alice", "bob"), ctx.reviewerNames)
    }

    @Test
    fun `reviewerNames deduplicates repeated usernames`() {
        val mr = minimalMr(
            reviewers = listOf(
                User("alice", "Alice"),
                User("alice", "Alice Again")
            )
        )
        val ctx = mr.toContext()
        assertEquals(setOf("alice"), ctx.reviewerNames)
    }

    @Test
    fun `reviewerNames result is consistent across multiple accesses`() {
        val ctx = minimalMr(reviewers = listOf(User("carol", "Carol"))).toContext()
        val first = ctx.reviewerNames
        val second = ctx.reviewerNames
        assertEquals(first, second)
        assertTrue(first === second, "Lazy result should be the same instance on repeated access")
    }

    // ── approverNames ────────────────────────────────────────────────────────

    @Test
    fun `approverNames returns empty set when MR has no approvals`() {
        val ctx = minimalMr().toContext()
        assertEquals(emptySet(), ctx.approverNames)
    }

    @Test
    fun `approverNames returns usernames of all approvers`() {
        val mr = minimalMr(
            approvals = listOf(
                Approval(User("dave", "Dave")),
                Approval(User("eve", "Eve"))
            )
        )
        val ctx = mr.toContext()
        assertEquals(setOf("dave", "eve"), ctx.approverNames)
    }

    @Test
    fun `approverNames counts only APPROVED reviews as the codeowners rule does`() {
        val mr = minimalMr(
            approvals = listOf(
                Approval(User("dave", "Dave")),
                Approval(User("eve", "Eve"), state = ReviewState.CHANGES_REQUESTED),
            )
        )
        assertEquals(setOf("dave"), mr.toContext().approverNames)
    }

    @Test
    fun `approverNames result is consistent across multiple accesses`() {
        val ctx = minimalMr(approvals = listOf(Approval(User("frank", "Frank")))).toContext()
        val first = ctx.approverNames
        val second = ctx.approverNames
        assertEquals(first, second)
        assertTrue(first === second, "Lazy result should be the same instance on repeated access")
    }

    // ── labelSet ─────────────────────────────────────────────────────────────

    @Test
    fun `labelSet returns empty set when MR has no labels`() {
        val ctx = minimalMr().toContext()
        assertEquals(emptySet(), ctx.labelSet)
    }

    @Test
    fun `labelSet contains all labels`() {
        val mr = minimalMr(labels = listOf("bug", "enhancement", "security"))
        val ctx = mr.toContext()
        assertEquals(setOf("bug", "enhancement", "security"), ctx.labelSet)
    }

    @Test
    fun `labelSet supports constant time membership testing`() {
        val mr = minimalMr(labels = listOf("bug", "reviewed"))
        val ctx = mr.toContext()
        assertTrue("bug" in ctx.labelSet)
        assertFalse("missing-label" in ctx.labelSet)
    }

    @Test
    fun `labelSet result is consistent across multiple accesses`() {
        val ctx = minimalMr(labels = listOf("a", "b")).toContext()
        val first = ctx.labelSet
        val second = ctx.labelSet
        assertEquals(first, second)
        assertTrue(first === second, "Lazy result should be the same instance on repeated access")
    }

    // ── changedPaths ──────────────────────────────────────────────────────────

    @Test
    fun `changedPaths returns empty set when MR has no changes`() {
        val ctx = minimalMr().toContext()
        assertEquals(emptySet(), ctx.changedPaths)
    }

    @Test
    fun `changedPaths contains all file paths`() {
        val mr = minimalMr(
            changes = listOf(
                FileChange("/src/Foo.kt", ChangeType.ADDED, 10, 0),
                FileChange("/src/Bar.kt", ChangeType.MODIFIED, 5, 3)
            )
        )
        val ctx = mr.toContext()
        assertEquals(setOf("/src/Foo.kt", "/src/Bar.kt"), ctx.changedPaths)
    }

    // ── totalLinesChanged ─────────────────────────────────────────────────────

    @Test
    fun `totalLinesChanged is zero for MR with no changes`() {
        val ctx = minimalMr().toContext()
        assertEquals(0, ctx.totalLinesChanged)
    }

    @Test
    fun `totalLinesChanged sums additions and deletions across all files`() {
        val mr = minimalMr(
            changes = listOf(
                FileChange("/src/A.kt", ChangeType.ADDED, 50, 0),
                FileChange("/src/B.kt", ChangeType.MODIFIED, 10, 5),
                FileChange("/src/C.kt", ChangeType.DELETED, 0, 20)
            )
        )
        val ctx = mr.toContext()
        // 50+0 + 10+5 + 0+20 = 85
        assertEquals(85, ctx.totalLinesChanged)
    }

    @Test
    fun `totalLinesChanged result is consistent across multiple accesses`() {
        val mr = minimalMr(
            changes = listOf(FileChange("/src/X.kt", ChangeType.ADDED, 7, 3))
        )
        val ctx = mr.toContext()
        assertEquals(ctx.totalLinesChanged, ctx.totalLinesChanged)
    }

    // ── isDraft ───────────────────────────────────────────────────────────────

    @Test
    fun `isDraft reflects draft flag when true`() {
        val ctx = minimalMr(draft = true).toContext()
        assertTrue(ctx.isDraft)
    }

    @Test
    fun `isDraft reflects draft flag when false`() {
        val ctx = minimalMr(draft = false).toContext()
        assertFalse(ctx.isDraft)
    }

    // ── mr reference ─────────────────────────────────────────────────────────

    @Test
    fun `ctx mr is the same MergeRequest instance passed to constructor`() {
        val mr = minimalMr()
        val ctx = mr.toContext()
        assertTrue(ctx.mr === mr, "ValidationContext.mr should be the original MergeRequest")
    }

    // ── toContext extension ───────────────────────────────────────────────────

    @Test
    fun `toContext creates a new ValidationContext each time`() {
        val mr = minimalMr()
        val ctx1 = mr.toContext()
        val ctx2 = mr.toContext()
        // Different instances, same data
        assertFalse(ctx1 === ctx2, "toContext() should create a new instance each call")
        assertEquals(ctx1.reviewerNames, ctx2.reviewerNames)
    }
}
