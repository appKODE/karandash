package io.karandash.validators

import io.karandash.models.Approval
import io.karandash.models.ApprovalMethod
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.validators.approval.ApprovalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApprovalValidatorTest {
    // ── Backward-compatible flat config tests ───────────────────────────

    @Test
    fun testSufficientApprovals() {
        val config = ApprovalConfig(minApprovals = 2)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "reviewer1", name = "Reviewer One")),
                Approval(user = User(username = "reviewer2", name = "Reviewer Two")),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings with sufficient approvals")
    }

    @Test
    fun testInsufficientApprovals() {
        val config = ApprovalConfig(minApprovals = 3)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "reviewer1", name = "Reviewer One")),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("approval", findings[0].rule)
        assertTrue(findings[0].message.contains("1 of 3"))
    }

    @Test
    fun testNoApprovalsRequired() {
        val config = ApprovalConfig(minApprovals = 0)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(approvals = emptyList())

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when no approvals required")
    }

    @Test
    fun testRequiredApproversPresent() {
        val config = ApprovalConfig(
            minApprovals = 1,
            requiredApprovers = listOf("tech-lead", "senior-dev"),
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "tech-lead", name = "Tech Lead")),
                Approval(user = User(username = "senior-dev", name = "Senior Dev")),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when all required approvers present")
    }

    @Test
    fun testRequiredApproversMissing() {
        val config = ApprovalConfig(
            minApprovals = 1,
            requiredApprovers = listOf("tech-lead", "security-team"),
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "junior-dev", name = "Junior Dev")),
            ),
        )

        val findings = validator.validate(mr)
        // All policy finds a failing sub-policy for requiredApprovers
        val reqFinding = findings.first { it.message.contains("Missing required approvers") }
        assertEquals(Severity.ERROR, reqFinding.severity)
        assertEquals("approval", reqFinding.rule)
        assertTrue(reqFinding.message.contains("tech-lead"))
        assertTrue(reqFinding.message.contains("security-team"))
    }

    @Test
    fun testPartialRequiredApprovers() {
        val config = ApprovalConfig(
            minApprovals = 1,
            requiredApprovers = listOf("tech-lead", "security-team", "qa-lead"),
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "tech-lead", name = "Tech Lead")),
            ),
        )

        val findings = validator.validate(mr)
        val reqFinding = findings.first { it.message.contains("Missing required approvers") }
        assertTrue(reqFinding.message.contains("security-team"))
        assertTrue(reqFinding.message.contains("qa-lead"))
    }

    @Test
    fun testBothMinApprovalsAndRequiredApprovers() {
        val config = ApprovalConfig(
            minApprovals = 2,
            requiredApprovers = listOf("tech-lead"),
        )
        val validator = ApprovalValidator(config)

        // Insufficient count
        val mrInsufficientCount = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "tech-lead", name = "Tech Lead")),
            ),
        )
        val findingsInsufficientCount = validator.validate(mrInsufficientCount)
        assertTrue(findingsInsufficientCount.any { it.message.contains("1 of 2") })

        // Missing required
        val mrMissingRequired = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "dev1", name = "Dev One")),
                Approval(user = User(username = "dev2", name = "Dev Two")),
            ),
        )
        val findingsMissingRequired = validator.validate(mrMissingRequired)
        assertTrue(findingsMissingRequired.any { it.message.contains("tech-lead") })

        // Valid
        val mrValid = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "tech-lead", name = "Tech Lead")),
                Approval(user = User(username = "dev1", name = "Dev One")),
            ),
        )
        val findingsValid = validator.validate(mrValid)
        assertTrue(findingsValid.isEmpty())
    }

    @Test
    fun `noChangesSinceApproval warns when commit is newer than approval`() {
        val config = ApprovalConfig(
            minApprovals = 1,
            noChangesSinceApproval = true,
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(
                    user = User(username = "reviewer1", name = "Reviewer One"),
                    approvedAt = "2024-01-01T10:00:00Z",
                ),
            ),
            commits = listOf(
                Commit(
                    sha = "abc123",
                    message = "Recent commit",
                    author = CommitAuthor(name = "Test User", email = "test@example.com"),
                    timestamp = "2024-01-01T13:00:00Z",
                ),
            ),
        )

        val findings = validator.validate(mr)
        // The flat config with noChangesSinceApproval=true synthesizes FreshApprovals(1)
        assertTrue(findings.isNotEmpty())
        assertTrue(findings.any { it.message.contains("fresh approvals") || it.message.contains("after") })
    }

    @Test
    fun `noChangesSinceApproval passes when approval is newer than commits`() {
        val config = ApprovalConfig(
            minApprovals = 1,
            noChangesSinceApproval = true,
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(
                    user = User(username = "reviewer1", name = "Reviewer One"),
                    approvedAt = "2024-01-01T13:00:00Z",
                ),
            ),
            commits = listOf(
                Commit(
                    sha = "abc123",
                    message = "Older commit",
                    author = CommitAuthor(name = "Test User", email = "test@example.com"),
                    timestamp = "2024-01-01T10:00:00Z",
                ),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `negative minApprovals throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            ApprovalConfig(minApprovals = -1)
        }
    }

    @Test
    fun `exactly at minApprovals boundary passes`() {
        val config = ApprovalConfig(minApprovals = 3)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "reviewer1", name = "Reviewer One")),
                Approval(user = User(username = "reviewer2", name = "Reviewer Two")),
                Approval(user = User(username = "reviewer3", name = "Reviewer Three")),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Exactly 3 approvals with minApprovals=3 should pass")
    }

    // ── Flat config: noVeto ─────────────────────────────────────────────

    @Test
    fun `noVeto flat config detects changes-requested reviews`() {
        val config = ApprovalConfig(minApprovals = 0, noVeto = true)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(
                    user = User(username = "reviewer1", name = "Reviewer"),
                    state = ReviewState.CHANGES_REQUESTED,
                ),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("reviewer1"))
    }

    @Test
    fun `noVeto flat config passes when no vetoes`() {
        val config = ApprovalConfig(minApprovals = 0, noVeto = true)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "reviewer1", name = "Reviewer")),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    // ── Flat config: noSelfApproval ─────────────────────────────────────

    @Test
    fun `noSelfApproval flat config detects author self-approval`() {
        val author = User(username = "author", name = "Author")
        val config = ApprovalConfig(minApprovals = 0, noSelfApproval = true)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(Approval(user = author)),
        ).copy(author = author)

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("author"))
    }

    @Test
    fun `noSelfApproval suggests removing the author from reviewers`() {
        val author = User(username = "author", name = "Author")
        val validator = ApprovalValidator(ApprovalConfig(minApprovals = 0, noSelfApproval = true))
        val mr = createTestMergeRequest(
            approvals = listOf(Approval(user = author)),
            reviewers = listOf(author, User(username = "bob", name = "Bob")),
        ).copy(author = author)

        val fix = validator
            .validate(mr)
            .single()
            .fixSuggestions
            .single() as FixSuggestion.Custom

        assertEquals("remove-review", fix.type)
        assertEquals("Remove the MR author author from reviewers", fix.description)
    }

    @Test
    fun `noSelfApproval suggests no removal when the author is not a reviewer or the reviewer is someone else`() {
        val author = User(username = "author", name = "Author")
        val bob = User(username = "bob", name = "Bob")
        val validator = ApprovalValidator(ApprovalConfig(minApprovals = 0, noSelfApproval = true))
        val mr = createTestMergeRequest(
            approvals = listOf(Approval(user = author)),
            reviewers = listOf(bob),
        ).copy(author = author)

        val finding = validator.validate(mr).single()

        assertTrue(finding.fixSuggestions.isEmpty(), "bob is a reviewer, not the self-approving author: ${finding.fixSuggestions}")
    }

    // ── Nested policy config ────────────────────────────────────────────

    @Test
    fun `explicit policy overrides flat config`() {
        val config = ApprovalConfig(
            minApprovals = 10, // would fail if used
            policy = ApprovalPolicy.MinApprovals(count = 1),
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "reviewer1", name = "Reviewer One")),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Explicit policy should override flat minApprovals")
    }

    @Test
    fun `nested All policy reports individual failures`() {
        val config = ApprovalConfig(
            policy = ApprovalPolicy.All(
                policies = listOf(
                    ApprovalPolicy.MinApprovals(count = 3),
                    ApprovalPolicy.RequiredReviewers(usernames = listOf("missing-dev")),
                ),
            ),
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = listOf(
                Approval(user = User(username = "reviewer1", name = "Reviewer One")),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size)
        assertTrue(findings.any { it.message.contains("1 of 3") })
        assertTrue(findings.any { it.message.contains("missing-dev") })
    }

    // ── Fix suggestions ─────────────────────────────────────────────────

    @Test
    fun `required reviewer failure generates request-review fix suggestions`() {
        val config = ApprovalConfig(
            minApprovals = 0,
            requiredApprovers = listOf("alice", "bob"),
        )
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(approvals = emptyList())

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)

        val fixes = findings[0].fixSuggestions
        assertEquals(2, fixes.size)
        assertTrue(fixes.all { it is FixSuggestion.Custom })
        assertTrue(fixes.all { (it as FixSuggestion.Custom).type == "request-review" })
        assertTrue(fixes.any { (it as FixSuggestion.Custom).description.contains("alice") })
        assertTrue(fixes.any { (it as FixSuggestion.Custom).description.contains("bob") })
    }

    @Test
    fun `minApprovals failure suggests review from assigned reviewers`() {
        val config = ApprovalConfig(minApprovals = 2)
        val validator = ApprovalValidator(config)

        val mr = createTestMergeRequest(
            approvals = emptyList(),
            reviewers = listOf(
                User(username = "alice", name = "Alice"),
                User(username = "bob", name = "Bob"),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)

        val fixes = findings[0].fixSuggestions
        assertEquals(2, fixes.size)
        assertTrue(fixes.any { (it as FixSuggestion.Custom).description.contains("alice") })
        assertTrue(fixes.any { (it as FixSuggestion.Custom).description.contains("bob") })
    }

    // ── Config resolution ───────────────────────────────────────────────

    @Test
    fun `resolvePolicy with no policy and defaults produces MinApprovals 1`() {
        val config = ApprovalConfig()
        val policy = config.resolvePolicy()
        val minPolicy = policy as ApprovalPolicy.MinApprovals
        assertEquals(1, minPolicy.count)
    }

    @Test
    fun `resolvePolicy with multiple flat fields produces All`() {
        val config = ApprovalConfig(
            minApprovals = 2,
            noVeto = true,
            noSelfApproval = true,
        )
        val policy = config.resolvePolicy()
        val allPolicy = policy as ApprovalPolicy.All
        assertEquals(3, allPolicy.policies.size)
    }

    @Test
    fun `resolvePolicy with zero minApprovals and no flags produces MinApprovals 0`() {
        val config = ApprovalConfig(minApprovals = 0)
        val policy = config.resolvePolicy()
        val minPolicy = policy as ApprovalPolicy.MinApprovals
        assertEquals(0, minPolicy.count)
    }

    // ── approval methods ────────────────────────────────────────

    private val signalApprovals = listOf(
        Approval(user = User("bob", "Bob"), method = ApprovalMethod.COMMENT_KEYWORD),
        Approval(user = User("carol", "Carol"), method = ApprovalMethod.EMOJI_REACTION),
    )

    @Test
    fun `comment and reaction approvals count when methods list them`() {
        val config = ApprovalConfig(
            minApprovals = 2,
            methods = listOf(ApprovalMethod.COMMENT_KEYWORD, ApprovalMethod.EMOJI_REACTION),
        )

        val findings = ApprovalValidator(config).validate(createTestMergeRequest(approvals = signalApprovals))

        assertTrue(findings.isEmpty(), "comment and reaction approvals should satisfy minApprovals")
    }

    @Test
    fun `comment and reaction approvals are ignored by default`() {
        val findings = ApprovalValidator(ApprovalConfig(minApprovals = 1)).validate(createTestMergeRequest(approvals = signalApprovals))

        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("0 of 1"), findings[0].message)
    }

    @Test
    fun `an unlisted method does not count`() {
        val config = ApprovalConfig(minApprovals = 2, methods = listOf(ApprovalMethod.COMMENT_KEYWORD))

        val findings = ApprovalValidator(config).validate(createTestMergeRequest(approvals = signalApprovals))

        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("1 of 2"), findings[0].message)
    }
}
