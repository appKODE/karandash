package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.CiJobStatus
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for all MR data predicates registered by [MrDataPredicates].
 */
class MrDataPredicatesTest {
    @Suppress("LongParameterList") // juts test function
    private fun makeMr(
        description: String? = null,
        approvals: List<Approval> = emptyList(),
        reviewers: List<User> = emptyList(),
        hasConflicts: Boolean? = null,
        state: MergeRequestState = MergeRequestState.OPENED,
        ciStatus: String? = null,
        ciJobs: List<CiJobStatus> = emptyList(),
        changes: List<FileChange> = emptyList(),
        commits: List<Commit> = emptyList(),
        labels: List<String> = emptyList(),
        mergeStatus: String? = null,
        sourceBranch: String = "feature/x",
        targetBranch: String = "main",
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "test",
            description = description,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            state = state,
            draft = false,
            labels = labels,
            commits = commits,
            changes = changes,
            approvals = approvals,
            reviewers = reviewers,
            hasConflicts = hasConflicts,
            ciStatus = ciStatus,
            ciJobs = ciJobs,
            mergeStatus = mergeStatus,
        )

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val ctx = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        val ev = ExpressionEvaluator(ctx)
        ev.registerAll(registry)
        return ev
    }

    private fun commit(
        message: String = "fix",
        email: String = "alice@example.com",
    ): Commit =
        Commit(
            sha = "abc123",
            message = message,
            author = CommitAuthor(name = "Alice", email = email),
            timestamp = "2024-01-01T00:00:00Z",
        )

    // ── has_description ──────────────────────────────────────────────────

    @Test
    fun `has_description returns true when description present`() {
        val ev = evaluator(makeMr(description = "Some description"))
        assertTrue(ev.evaluate("has_description"))
    }

    @Test
    fun `has_description returns false when description null`() {
        val ev = evaluator(makeMr(description = null))
        assertFalse(ev.evaluate("has_description"))
    }

    @Test
    fun `has_description returns false when description blank`() {
        val ev = evaluator(makeMr(description = "   "))
        assertFalse(ev.evaluate("has_description"))
    }

    // ── has_approvals ────────────────────────────────────────────────────

    @Test
    fun `has_approvals returns true with approvals`() {
        val ev = evaluator(
            makeMr(approvals = listOf(Approval(user = User(username = "bob", name = "Bob")))),
        )
        assertTrue(ev.evaluate("has_approvals"))
    }

    @Test
    fun `has_approvals returns false when empty`() {
        val ev = evaluator(makeMr(approvals = emptyList()))
        assertFalse(ev.evaluate("has_approvals"))
    }

    // ── has_reviewers ────────────────────────────────────────────────────

    @Test
    fun `has_reviewers returns true with reviewers`() {
        val ev = evaluator(
            makeMr(reviewers = listOf(User(username = "bob", name = "Bob"))),
        )
        assertTrue(ev.evaluate("has_reviewers"))
    }

    @Test
    fun `has_reviewers returns false when empty`() {
        val ev = evaluator(makeMr(reviewers = emptyList()))
        assertFalse(ev.evaluate("has_reviewers"))
    }

    // ── has_conflicts ────────────────────────────────────────────────────

    @Test
    fun `has_conflicts returns true when conflicts exist`() {
        val ev = evaluator(makeMr(hasConflicts = true))
        assertTrue(ev.evaluate("has_conflicts"))
    }

    @Test
    fun `has_conflicts returns false when no conflicts`() {
        val ev = evaluator(makeMr(hasConflicts = false))
        assertFalse(ev.evaluate("has_conflicts"))
    }

    @Test
    fun `has_conflicts returns false when null`() {
        val ev = evaluator(makeMr(hasConflicts = null))
        assertFalse(ev.evaluate("has_conflicts"))
    }

    // ── is_merged / is_closed / is_opened ────────────────────────────────

    @Test
    fun `is_merged returns true for merged state`() {
        val ev = evaluator(makeMr(state = MergeRequestState.MERGED))
        assertTrue(ev.evaluate("is_merged"))
    }

    @Test
    fun `is_merged returns false for opened state`() {
        val ev = evaluator(makeMr(state = MergeRequestState.OPENED))
        assertFalse(ev.evaluate("is_merged"))
    }

    @Test
    fun `is_closed returns true for closed state`() {
        val ev = evaluator(makeMr(state = MergeRequestState.CLOSED))
        assertTrue(ev.evaluate("is_closed"))
    }

    @Test
    fun `is_closed returns false for opened state`() {
        val ev = evaluator(makeMr(state = MergeRequestState.OPENED))
        assertFalse(ev.evaluate("is_closed"))
    }

    @Test
    fun `is_opened returns true for opened state`() {
        val ev = evaluator(makeMr(state = MergeRequestState.OPENED))
        assertTrue(ev.evaluate("is_opened"))
    }

    @Test
    fun `is_opened returns false for closed state`() {
        val ev = evaluator(makeMr(state = MergeRequestState.CLOSED))
        assertFalse(ev.evaluate("is_opened"))
    }

    // ── has_ci ───────────────────────────────────────────────────────────

    @Test
    fun `has_ci returns true when ci status present`() {
        val ev = evaluator(makeMr(ciStatus = "success"))
        assertTrue(ev.evaluate("has_ci"))
    }

    @Test
    fun `has_ci returns false when ci status null`() {
        val ev = evaluator(makeMr(ciStatus = null))
        assertFalse(ev.evaluate("has_ci"))
    }

    // ── description_matches ──────────────────────────────────────────────

    @Test
    fun `description_matches returns true when pattern matches`() {
        val ev = evaluator(makeMr(description = "Fixes JIRA-123"))
        assertTrue(ev.evaluate("""description_matches("JIRA-\d+")"""))
    }

    @Test
    fun `description_matches returns false when no match`() {
        val ev = evaluator(makeMr(description = "No ticket here"))
        assertFalse(ev.evaluate("""description_matches("JIRA-\d+")"""))
    }

    @Test
    fun `description_matches returns false when description null`() {
        val ev = evaluator(makeMr(description = null))
        assertFalse(ev.evaluate("""description_matches("JIRA-\d+")"""))
    }

    // ── commit_message_matches ───────────────────────────────────────────

    @Test
    fun `commit_message_matches returns true when any commit matches`() {
        val ev = evaluator(
            makeMr(commits = listOf(commit(message = "fix: resolve bug"), commit(message = "chore: update"))),
        )
        assertTrue(ev.evaluate("""commit_message_matches("^fix:")"""))
    }

    @Test
    fun `commit_message_matches returns false when none match`() {
        val ev = evaluator(
            makeMr(commits = listOf(commit(message = "chore: update"))),
        )
        assertFalse(ev.evaluate("""commit_message_matches("^fix:")"""))
    }

    // ── source_branch_matches ────────────────────────────────────────────

    @Test
    fun `source_branch_matches returns true when pattern matches`() {
        val ev = evaluator(makeMr(sourceBranch = "feature/new-thing"))
        assertTrue(ev.evaluate("""source_branch_matches("^feature/")"""))
    }

    @Test
    fun `source_branch_matches returns false when no match`() {
        val ev = evaluator(makeMr(sourceBranch = "bugfix/thing"))
        assertFalse(ev.evaluate("""source_branch_matches("^feature/")"""))
    }

    // ── target_branch_matches ────────────────────────────────────────────

    @Test
    fun `target_branch_matches returns true when pattern matches`() {
        val ev = evaluator(makeMr(targetBranch = "release/1.0"))
        assertTrue(ev.evaluate("""target_branch_matches("^release/")"""))
    }

    @Test
    fun `target_branch_matches returns false when no match`() {
        val ev = evaluator(makeMr(targetBranch = "main"))
        assertFalse(ev.evaluate("""target_branch_matches("^release/")"""))
    }

    // ── ci_status ────────────────────────────────────────────────────────

    @Test
    fun `ci_status returns true when status matches`() {
        val ev = evaluator(makeMr(ciStatus = "success"))
        assertTrue(ev.evaluate("""ci_status("success")"""))
    }

    @Test
    fun `ci_status returns false for different status`() {
        val ev = evaluator(makeMr(ciStatus = "failed"))
        assertFalse(ev.evaluate("""ci_status("success")"""))
    }

    @Test
    fun `ci_status returns false when null`() {
        val ev = evaluator(makeMr(ciStatus = null))
        assertFalse(ev.evaluate("""ci_status("success")"""))
    }

    // ── ci_job_status ────────────────────────────────────────────────────

    @Test
    fun `ci_job_status returns true for matching job and status`() {
        val ev = evaluator(
            makeMr(ciJobs = listOf(CiJobStatus(name = "lint", status = "success"))),
        )
        assertTrue(ev.evaluate("""ci_job_status("lint", "success")"""))
    }

    @Test
    fun `ci_job_status returns false for wrong job name`() {
        val ev = evaluator(
            makeMr(ciJobs = listOf(CiJobStatus(name = "test", status = "success"))),
        )
        assertFalse(ev.evaluate("""ci_job_status("lint", "success")"""))
    }

    @Test
    fun `ci_job_status returns false for wrong status`() {
        val ev = evaluator(
            makeMr(ciJobs = listOf(CiJobStatus(name = "lint", status = "failed"))),
        )
        assertFalse(ev.evaluate("""ci_job_status("lint", "success")"""))
    }

    // ── change_type_exists ───────────────────────────────────────────────

    @Test
    fun `change_type_exists returns true when type present`() {
        val ev = evaluator(
            makeMr(changes = listOf(FileChange(path = "new.kt", changeType = ChangeType.ADDED))),
        )
        assertTrue(ev.evaluate("""change_type_exists("ADDED")"""))
    }

    @Test
    fun `change_type_exists returns false when type absent`() {
        val ev = evaluator(
            makeMr(changes = listOf(FileChange(path = "mod.kt", changeType = ChangeType.MODIFIED))),
        )
        assertFalse(ev.evaluate("""change_type_exists("ADDED")"""))
    }

    // ── file_extension_matches ───────────────────────────────────────────

    @Test
    fun `file_extension_matches returns true when extension present`() {
        val ev = evaluator(
            makeMr(changes = listOf(FileChange(path = "migration.sql", changeType = ChangeType.ADDED))),
        )
        assertTrue(ev.evaluate("""file_extension_matches(".sql")"""))
    }

    @Test
    fun `file_extension_matches returns false when extension absent`() {
        val ev = evaluator(
            makeMr(changes = listOf(FileChange(path = "Main.kt", changeType = ChangeType.MODIFIED))),
        )
        assertFalse(ev.evaluate("""file_extension_matches(".sql")"""))
    }

    // ── merge_status ─────────────────────────────────────────────────────

    @Test
    fun `merge_status returns true when status matches`() {
        val ev = evaluator(makeMr(mergeStatus = "can_be_merged"))
        assertTrue(ev.evaluate("""merge_status("can_be_merged")"""))
    }

    @Test
    fun `merge_status returns false when null`() {
        val ev = evaluator(makeMr(mergeStatus = null))
        assertFalse(ev.evaluate("""merge_status("can_be_merged")"""))
    }

    // ── label_matches ────────────────────────────────────────────────────

    @Test
    fun `label_matches returns true when any label matches regex`() {
        val ev = evaluator(makeMr(labels = listOf("jira/PROJ-123", "urgent")))
        assertTrue(ev.evaluate("""label_matches("jira/.*")"""))
    }

    @Test
    fun `label_matches returns false when no label matches`() {
        val ev = evaluator(makeMr(labels = listOf("bug", "urgent")))
        assertFalse(ev.evaluate("""label_matches("jira/.*")"""))
    }

    // ── approved_by ──────────────────────────────────────────────────────

    @Test
    fun `approved_by returns true when user approved`() {
        val ev = evaluator(
            makeMr(approvals = listOf(Approval(user = User(username = "alice", name = "Alice")))),
        )
        assertTrue(ev.evaluate("""approved_by("alice")"""))
    }

    @Test
    fun `approved_by returns false when user not in approvals`() {
        val ev = evaluator(
            makeMr(approvals = listOf(Approval(user = User(username = "bob", name = "Bob")))),
        )
        assertFalse(ev.evaluate("""approved_by("alice")"""))
    }

    // ── reviewer_is ──────────────────────────────────────────────────────

    @Test
    fun `reviewer_is returns true when user is reviewer`() {
        val ev = evaluator(
            makeMr(reviewers = listOf(User(username = "bob", name = "Bob"))),
        )
        assertTrue(ev.evaluate("""reviewer_is("bob")"""))
    }

    @Test
    fun `reviewer_is returns false when user not reviewer`() {
        val ev = evaluator(
            makeMr(reviewers = listOf(User(username = "carol", name = "Carol"))),
        )
        assertFalse(ev.evaluate("""reviewer_is("bob")"""))
    }

    // ── commit_author_matches ────────────────────────────────────────────

    @Test
    fun `commit_author_matches returns true when email matches`() {
        val ev = evaluator(
            makeMr(commits = listOf(commit(email = "dev@example.com"))),
        )
        assertTrue(ev.evaluate("""commit_author_matches("@example\.com")"""))
    }

    @Test
    fun `commit_author_matches returns false when no match`() {
        val ev = evaluator(
            makeMr(commits = listOf(commit(email = "dev@other.org"))),
        )
        assertFalse(ev.evaluate("""commit_author_matches("@example\.com")"""))
    }

    // ── file_modified ────────────────────────────────────────────────────

    @Test
    fun `file_modified returns true when path present`() {
        val ev = evaluator(
            makeMr(
                changes = listOf(FileChange(path = "README.md", changeType = ChangeType.MODIFIED)),
            ),
        )
        assertTrue(ev.evaluate("""file_modified("README.md")"""))
    }

    @Test
    fun `file_modified returns false when path absent`() {
        val ev = evaluator(
            makeMr(
                changes = listOf(FileChange(path = "src/Main.kt", changeType = ChangeType.MODIFIED)),
            ),
        )
        assertFalse(ev.evaluate("""file_modified("README.md")"""))
    }
}
