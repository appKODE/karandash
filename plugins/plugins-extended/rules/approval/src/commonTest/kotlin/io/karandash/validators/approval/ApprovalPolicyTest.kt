package io.karandash.validators.approval

import io.karandash.models.Approval
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalPolicyTest {
    private val alice = User(username = "alice", name = "Alice")
    private val bob = User(username = "bob", name = "Bob")
    private val carol = User(username = "carol", name = "Carol")
    private val author = User(username = "author", name = "Author")

    private fun approved(
        user: User,
        at: String? = null
    ): Approval = Approval(user = user, approvedAt = at, state = ReviewState.APPROVED)

    private fun changesRequested(user: User): Approval = Approval(user = user, state = ReviewState.CHANGES_REQUESTED)

    private fun commented(user: User): Approval = Approval(user = user, state = ReviewState.COMMENTED)

    private fun mr(
        approvals: List<Approval> = emptyList(),
        mrAuthor: User = author,
        commits: List<Commit> = emptyList(),
        reviewers: List<User> = emptyList(),
    ): MergeRequest =
        createTestMergeRequest(
            approvals = approvals,
            commits = commits,
            reviewers = reviewers,
        ).copy(author = mrAuthor)

    // ── MinApprovals ────────────────────────────────────────────────────

    @Test
    fun `MinApprovals passes when enough approvals`() {
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.MinApprovals(count = 2),
            listOf(approved(alice), approved(bob)),
            mr(approvals = listOf(approved(alice), approved(bob))),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `MinApprovals fails when insufficient approvals`() {
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.MinApprovals(count = 3),
            listOf(approved(alice)),
            mr(approvals = listOf(approved(alice))),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("1 of 3"))
    }

    @Test
    fun `MinApprovals ignores non-approved reviews`() {
        val reviews = listOf(approved(alice), changesRequested(bob), commented(carol))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.MinApprovals(count = 2),
            reviews,
            mr(approvals = reviews),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("1 of 2"))
    }

    @Test
    fun `MinApprovals zero count always passes`() {
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.MinApprovals(count = 0),
            emptyList(),
            mr(),
        )
        assertTrue(result.satisfied)
    }

    // ── RequiredReviewers ───────────────────────────────────────────────

    @Test
    fun `RequiredReviewers passes when all present`() {
        val reviews = listOf(approved(alice), approved(bob))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.RequiredReviewers(usernames = listOf("alice", "bob")),
            reviews,
            mr(approvals = reviews),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `RequiredReviewers fails when some missing`() {
        val reviews = listOf(approved(alice))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.RequiredReviewers(usernames = listOf("alice", "bob")),
            reviews,
            mr(approvals = reviews),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("bob"))
        assertEquals(listOf("bob"), result.details)
    }

    @Test
    fun `RequiredReviewers ignores non-approved reviews`() {
        val reviews = listOf(approved(alice), changesRequested(bob))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.RequiredReviewers(usernames = listOf("alice", "bob")),
            reviews,
            mr(approvals = reviews),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("bob"))
    }

    // ── GroupApproval ───────────────────────────────────────────────────

    @Test
    fun `GroupApproval passes when enough group members approved`() {
        val reviews = listOf(approved(alice), approved(bob))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.GroupApproval(
                group = "backend",
                members = listOf("alice", "bob", "carol"),
                minCount = 2,
            ),
            reviews,
            mr(approvals = reviews),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `GroupApproval fails when insufficient group members approved`() {
        val reviews = listOf(approved(alice))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.GroupApproval(
                group = "backend",
                members = listOf("alice", "bob", "carol"),
                minCount = 2,
            ),
            reviews,
            mr(approvals = reviews),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("backend"))
        assertTrue(result.reason.contains("1 of 2"))
    }

    @Test
    fun `GroupApproval ignores approvals from non-members`() {
        val outsider = User(username = "outsider", name = "Outsider")
        val reviews = listOf(approved(outsider))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.GroupApproval(
                group = "backend",
                members = listOf("alice", "bob"),
                minCount = 1,
            ),
            reviews,
            mr(approvals = reviews),
        )
        assertFalse(result.satisfied)
    }

    // ── All (AND) ───────────────────────────────────────────────────────

    @Test
    fun `All passes when all sub-policies satisfied`() {
        val reviews = listOf(approved(alice), approved(bob))
        val policy = ApprovalPolicy.All(
            policies = listOf(
                ApprovalPolicy.MinApprovals(count = 2),
                ApprovalPolicy.RequiredReviewers(usernames = listOf("alice")),
            ),
        )
        val result = PolicyEvaluator.evaluate(policy, reviews, mr(approvals = reviews))
        assertTrue(result.satisfied)
    }

    @Test
    fun `All fails when any sub-policy unsatisfied`() {
        val reviews = listOf(approved(alice))
        val policy = ApprovalPolicy.All(
            policies = listOf(
                ApprovalPolicy.MinApprovals(count = 2),
                ApprovalPolicy.RequiredReviewers(usernames = listOf("alice")),
            ),
        )
        val result = PolicyEvaluator.evaluate(policy, reviews, mr(approvals = reviews))
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("1 of 2 policies"))
    }

    // ── Any (OR) ────────────────────────────────────────────────────────

    @Test
    fun `Any passes when at least one sub-policy satisfied`() {
        val reviews = listOf(approved(alice))
        val policy = ApprovalPolicy.Any(
            policies = listOf(
                ApprovalPolicy.MinApprovals(count = 3),
                ApprovalPolicy.RequiredReviewers(usernames = listOf("alice")),
            ),
        )
        val result = PolicyEvaluator.evaluate(policy, reviews, mr(approvals = reviews))
        assertTrue(result.satisfied)
    }

    @Test
    fun `Any fails when no sub-policy satisfied`() {
        val reviews = listOf(approved(alice))
        val policy = ApprovalPolicy.Any(
            policies = listOf(
                ApprovalPolicy.MinApprovals(count = 3),
                ApprovalPolicy.RequiredReviewers(usernames = listOf("bob")),
            ),
        )
        val result = PolicyEvaluator.evaluate(policy, reviews, mr(approvals = reviews))
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("None of 2"))
    }

    // ── Not ─────────────────────────────────────────────────────────────

    @Test
    fun `Not inverts a satisfied policy`() {
        val reviews = listOf(approved(alice))
        val policy = ApprovalPolicy.Not(ApprovalPolicy.MinApprovals(count = 1))
        val result = PolicyEvaluator.evaluate(policy, reviews, mr(approvals = reviews))
        assertFalse(result.satisfied)
    }

    @Test
    fun `Not inverts an unsatisfied policy`() {
        val policy = ApprovalPolicy.Not(ApprovalPolicy.MinApprovals(count = 1))
        val result = PolicyEvaluator.evaluate(policy, emptyList(), mr())
        assertTrue(result.satisfied)
    }

    // ── NoVeto ──────────────────────────────────────────────────────────

    @Test
    fun `NoVeto passes when no changes-requested reviews`() {
        val reviews = listOf(approved(alice), commented(bob))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.NoVeto,
            reviews,
            mr(approvals = reviews),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `NoVeto fails when changes-requested review exists`() {
        val reviews = listOf(approved(alice), changesRequested(bob))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.NoVeto,
            reviews,
            mr(approvals = reviews),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("bob"))
    }

    @Test
    fun `NoVeto passes with empty review list`() {
        val result = PolicyEvaluator.evaluate(ApprovalPolicy.NoVeto, emptyList(), mr())
        assertTrue(result.satisfied)
    }

    // ── NoSelfApproval ──────────────────────────────────────────────────

    @Test
    fun `NoSelfApproval passes when author has not approved`() {
        val reviews = listOf(approved(alice))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.NoSelfApproval,
            reviews,
            mr(approvals = reviews, mrAuthor = author),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `NoSelfApproval fails when author has approved`() {
        val reviews = listOf(approved(author))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.NoSelfApproval,
            reviews,
            mr(approvals = reviews, mrAuthor = author),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("author"))
    }

    @Test
    fun `NoSelfApproval ignores non-approved self-reviews`() {
        val reviews = listOf(commented(author))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.NoSelfApproval,
            reviews,
            mr(approvals = reviews, mrAuthor = author),
        )
        assertTrue(result.satisfied)
    }

    // ── FreshApprovals ──────────────────────────────────────────────────

    @Test
    fun `FreshApprovals passes when approvals are after latest commit`() {
        val commits = listOf(
            Commit(
                sha = "abc",
                message = "commit",
                author = CommitAuthor(name = "Dev", email = "dev@test.com"),
                timestamp = "2024-01-01T10:00:00Z",
            ),
        )
        val reviews = listOf(approved(alice, at = "2024-01-01T12:00:00Z"))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.FreshApprovals(count = 1),
            reviews,
            mr(approvals = reviews, commits = commits),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `FreshApprovals fails when approvals are before latest commit`() {
        val commits = listOf(
            Commit(
                sha = "abc",
                message = "commit",
                author = CommitAuthor(name = "Dev", email = "dev@test.com"),
                timestamp = "2024-01-01T14:00:00Z",
            ),
        )
        val reviews = listOf(approved(alice, at = "2024-01-01T12:00:00Z"))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.FreshApprovals(count = 1),
            reviews,
            mr(approvals = reviews, commits = commits),
        )
        assertFalse(result.satisfied)
        assertTrue(result.reason.contains("0 of 1"))
    }

    @Test
    fun `FreshApprovals passes with no commits`() {
        val reviews = listOf(approved(alice, at = "2024-01-01T12:00:00Z"))
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.FreshApprovals(count = 1),
            reviews,
            mr(approvals = reviews),
        )
        assertTrue(result.satisfied)
    }

    @Test
    fun `FreshApprovals ignores approvals without timestamps`() {
        val commits = listOf(
            Commit(
                sha = "abc",
                message = "commit",
                author = CommitAuthor(name = "Dev", email = "dev@test.com"),
                timestamp = "2024-01-01T10:00:00Z",
            ),
        )
        val reviews = listOf(approved(alice)) // no approvedAt
        val result = PolicyEvaluator.evaluate(
            ApprovalPolicy.FreshApprovals(count = 1),
            reviews,
            mr(approvals = reviews, commits = commits),
        )
        assertFalse(result.satisfied)
    }

    // ── Nested composition ──────────────────────────────────────────────

    @Test
    fun `nested All-Any composition works`() {
        val reviews = listOf(approved(alice))
        val policy = ApprovalPolicy.All(
            policies = listOf(
                ApprovalPolicy.MinApprovals(count = 1),
                ApprovalPolicy.Any(
                    policies = listOf(
                        ApprovalPolicy.RequiredReviewers(usernames = listOf("alice")),
                        ApprovalPolicy.RequiredReviewers(usernames = listOf("bob")),
                    ),
                ),
            ),
        )
        val result = PolicyEvaluator.evaluate(policy, reviews, mr(approvals = reviews))
        assertTrue(result.satisfied)
    }

    @Test
    fun `deeply nested policy evaluates correctly`() {
        val reviews = listOf(approved(alice), approved(bob))
        val policy = ApprovalPolicy.All(
            policies = listOf(
                ApprovalPolicy.MinApprovals(count = 2),
                ApprovalPolicy.NoVeto,
                ApprovalPolicy.NoSelfApproval,
                ApprovalPolicy.Any(
                    policies = listOf(
                        ApprovalPolicy.GroupApproval(
                            group = "frontend",
                            members = listOf("alice"),
                            minCount = 1,
                        ),
                        ApprovalPolicy.GroupApproval(
                            group = "backend",
                            members = listOf("bob"),
                            minCount = 1,
                        ),
                    ),
                ),
            ),
        )
        val result = PolicyEvaluator.evaluate(
            policy,
            reviews,
            mr(approvals = reviews, mrAuthor = carol),
        )
        assertTrue(result.satisfied)
    }
}
