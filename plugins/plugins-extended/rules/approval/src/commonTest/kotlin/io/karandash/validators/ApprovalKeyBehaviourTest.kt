package io.karandash.validators

import io.karandash.models.Approval
import io.karandash.models.ApprovalMethod
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.validators.approval.ApprovalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalKeyBehaviourTest {
    private fun approval(
        username: String,
        state: ReviewState = ReviewState.APPROVED,
        at: String? = null,
        method: ApprovalMethod = ApprovalMethod.CODE_REVIEW,
    ) = Approval(User(username = username, name = username), approvedAt = at, state = state, method = method)

    private fun messages(
        config: ApprovalConfig,
        vararg approvals: Approval,
    ): List<String> =
        ApprovalValidator(config)
            .validate(
                createTestMergeRequest(
                    approvals = approvals.toList(),
                    commits = listOf(Commit("abc1234", "m", CommitAuthor("u", "e"), "2024-01-02T00:00:00Z")),
                ),
            ).map { it.message }

    @Test
    fun `minApprovals flags an MR with too few approvals`() {
        val config = ApprovalConfig(minApprovals = 2)
        assertTrue(messages(config, approval("a")).single().contains("Insufficient"))
        assertEquals(emptyList(), messages(config, approval("a"), approval("b")))
    }

    @Test
    fun `requiredApprovers flags an MR until every listed user approved`() {
        val config = ApprovalConfig(minApprovals = 0, requiredApprovers = listOf("alice"))
        assertEquals(1, messages(config, approval("bob")).size)
        assertEquals(emptyList(), messages(config, approval("alice")))
    }

    @Test
    fun `noVeto flags a changes requested review`() {
        val config = ApprovalConfig(noVeto = true)
        assertTrue(messages(config, approval("a"), approval("b", ReviewState.CHANGES_REQUESTED)).single().contains("Changes requested"))
        assertEquals(emptyList(), messages(config, approval("a")))
    }

    @Test
    fun `noSelfApproval flags an approval by the author`() {
        val config = ApprovalConfig(minApprovals = 0, noSelfApproval = true)
        assertEquals(1, messages(config, approval("testuser")).size)
        assertEquals(emptyList(), messages(config, approval("alice")))
    }

    @Test
    fun `freshApprovals flags an approval older than the last commit`() {
        val config = ApprovalConfig(minApprovals = 0, freshApprovals = true)
        assertEquals(1, messages(config, approval("a", at = "2024-01-01T00:00:00Z")).size)
        assertEquals(emptyList(), messages(config, approval("a", at = "2024-01-03T00:00:00Z")))
    }

    @Test
    fun `noChangesSinceApproval flags an approval older than the last commit`() {
        val config = ApprovalConfig(minApprovals = 0, noChangesSinceApproval = true)
        assertEquals(1, messages(config, approval("a", at = "2024-01-01T00:00:00Z")).size)
        assertEquals(emptyList(), messages(config, approval("a", at = "2024-01-03T00:00:00Z")))
    }

    @Test
    fun `policy replaces the flat keys`() {
        val config = ApprovalConfig(minApprovals = 1, policy = ApprovalPolicy.MinApprovals(count = 2))
        assertEquals(1, messages(config, approval("a")).size)
        assertEquals(emptyList(), messages(config, approval("a"), approval("b")))
    }

    @Test
    fun `methods counts only approvals given by a listed method`() {
        val comment = approval("a", method = ApprovalMethod.COMMENT_KEYWORD)
        assertEquals(1, messages(ApprovalConfig(), comment).size)
        val config = ApprovalConfig(methods = listOf(ApprovalMethod.CODE_REVIEW, ApprovalMethod.COMMENT_KEYWORD))
        assertEquals(emptyList(), messages(config, comment))
    }
}
