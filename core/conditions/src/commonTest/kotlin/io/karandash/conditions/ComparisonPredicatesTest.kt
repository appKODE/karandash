package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the comparison predicates in [CommonPredicates]:
 * `deletions`, `total_changes`, `commit_count`, `approval_count`,
 * `reviewer_count`, `label_count`, `description_length`.
 */
class ComparisonPredicatesTest {
    private fun makeMr(
        changes: List<FileChange> = emptyList(),
        commits: List<Commit> = emptyList(),
        approvals: List<Approval> = emptyList(),
        reviewers: List<User> = emptyList(),
        labels: List<String> = emptyList(),
        description: String? = null,
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "test",
            description = description,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/x",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = labels,
            commits = commits,
            changes = changes,
            approvals = approvals,
            reviewers = reviewers,
        )

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val ctx = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        val ev = ExpressionEvaluator(ctx)
        ev.registerAll(registry)
        return ev
    }

    private fun fileChange(
        additions: Int = 0,
        deletions: Int = 0,
    ): FileChange =
        FileChange(
            path = "src/File.kt",
            changeType = ChangeType.MODIFIED,
            additions = additions,
            deletions = deletions,
        )

    private fun commit(message: String = "fix"): Commit =
        Commit(
            sha = "abc123",
            message = message,
            author = CommitAuthor(name = "Alice", email = "alice@example.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )

    // ── deletions ────────────────────────────────────────────────────────

    @Test
    fun `deletions greater than threshold returns true when exceeded`() {
        val ev = evaluator(makeMr(changes = listOf(fileChange(deletions = 10))))
        assertTrue(ev.evaluate("deletions > 5"))
    }

    @Test
    fun `deletions greater than threshold returns false at boundary`() {
        val ev = evaluator(makeMr(changes = listOf(fileChange(deletions = 5))))
        assertFalse(ev.evaluate("deletions > 5"))
    }

    @Test
    fun `deletions less than threshold returns true when below`() {
        val ev = evaluator(makeMr(changes = listOf(fileChange(deletions = 3))))
        assertTrue(ev.evaluate("deletions < 5"))
    }

    @Test
    fun `deletions equals threshold returns true at exact value`() {
        val ev = evaluator(makeMr(changes = listOf(fileChange(deletions = 5))))
        assertTrue(ev.evaluate("deletions == 5"))
    }

    @Test
    fun `deletions equals threshold returns false when different`() {
        val ev = evaluator(makeMr(changes = listOf(fileChange(deletions = 6))))
        assertFalse(ev.evaluate("deletions == 5"))
    }

    // ── total_changes ────────────────────────────────────────────────────

    @Test
    fun `total changes greater than threshold returns true`() {
        val ev = evaluator(
            makeMr(changes = listOf(fileChange(additions = 30, deletions = 25))),
        )
        assertTrue(ev.evaluate("total_changes > 50"))
    }

    @Test
    fun `total changes greater than threshold returns false at boundary`() {
        val ev = evaluator(
            makeMr(changes = listOf(fileChange(additions = 25, deletions = 25))),
        )
        assertFalse(ev.evaluate("total_changes > 50"))
    }

    @Test
    fun `total changes equals threshold returns true`() {
        val ev = evaluator(
            makeMr(changes = listOf(fileChange(additions = 25, deletions = 25))),
        )
        assertTrue(ev.evaluate("total_changes == 50"))
    }

    // ── commit_count ─────────────────────────────────────────────────────

    @Test
    fun `commit count greater than threshold returns true`() {
        val ev = evaluator(makeMr(commits = listOf(commit(), commit(), commit())))
        assertTrue(ev.evaluate("commit_count > 2"))
    }

    @Test
    fun `commit count greater than threshold returns false at boundary`() {
        val ev = evaluator(makeMr(commits = listOf(commit(), commit())))
        assertFalse(ev.evaluate("commit_count > 2"))
    }

    @Test
    fun `commit count equals threshold returns true`() {
        val ev = evaluator(makeMr(commits = listOf(commit(), commit())))
        assertTrue(ev.evaluate("commit_count == 2"))
    }

    // ── approval_count ───────────────────────────────────────────────────

    @Test
    fun `approval count gte returns true when equal`() {
        val approvals = listOf(
            Approval(user = User(username = "bob", name = "Bob")),
            Approval(user = User(username = "carol", name = "Carol")),
        )
        val ev = evaluator(makeMr(approvals = approvals))
        assertTrue(ev.evaluate("approval_count >= 2"))
    }

    @Test
    fun `approval count gte returns false when below`() {
        val approvals = listOf(
            Approval(user = User(username = "bob", name = "Bob")),
        )
        val ev = evaluator(makeMr(approvals = approvals))
        assertFalse(ev.evaluate("approval_count >= 2"))
    }

    // ── reviewer_count ───────────────────────────────────────────────────

    @Test
    fun `reviewer count gte returns true when equal`() {
        val reviewers = listOf(
            User(username = "bob", name = "Bob"),
            User(username = "carol", name = "Carol"),
        )
        val ev = evaluator(makeMr(reviewers = reviewers))
        assertTrue(ev.evaluate("reviewer_count >= 2"))
    }

    @Test
    fun `reviewer count gte returns false when below`() {
        val ev = evaluator(makeMr(reviewers = listOf(User(username = "bob", name = "Bob"))))
        assertFalse(ev.evaluate("reviewer_count >= 2"))
    }

    // ── label_count ──────────────────────────────────────────────────────

    @Test
    fun `label count greater than threshold returns true`() {
        val ev = evaluator(makeMr(labels = listOf("bug", "urgent", "backend")))
        assertTrue(ev.evaluate("label_count > 2"))
    }

    @Test
    fun `label count greater than threshold returns false at boundary`() {
        val ev = evaluator(makeMr(labels = listOf("bug", "urgent")))
        assertFalse(ev.evaluate("label_count > 2"))
    }

    // ── description_length ───────────────────────────────────────────────

    @Test
    fun `description length greater than threshold returns true`() {
        val ev = evaluator(makeMr(description = "A".repeat(101)))
        assertTrue(ev.evaluate("description_length > 100"))
    }

    @Test
    fun `description length greater than threshold returns false at boundary`() {
        val ev = evaluator(makeMr(description = "A".repeat(100)))
        assertFalse(ev.evaluate("description_length > 100"))
    }

    @Test
    fun `description length with null description treats as zero`() {
        val ev = evaluator(makeMr(description = null))
        assertTrue(ev.evaluate("description_length == 0"))
    }

    @Test
    fun `function-call syntax with operator name evaluates`() {
        val mr = makeMr(changes = listOf(fileChange(deletions = 3)))
        assertTrue(evaluator(mr).evaluate("deletions(GT, 2)"))
        assertTrue(evaluator(mr).evaluate("deletions(GT, 5) || deletions(LT, 5)"))
    }

    @Test
    fun `unknown comparison operator is a malformed condition instead of silently false`() {
        val mr = makeMr(changes = listOf(fileChange(deletions = 3)))
        val error = assertFailsWith<IllegalArgumentException> {
            evaluator(mr).evaluate("deletions(BOGUS, 2) || deletions(GT, 2)")
        }
        assertContains(error.message.orEmpty(), "Invalid comparison operator 'BOGUS'")
    }
}
