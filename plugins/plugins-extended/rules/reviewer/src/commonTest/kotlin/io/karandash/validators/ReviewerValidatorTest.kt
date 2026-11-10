package io.karandash.validators

import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewerValidatorTest {
    @Test
    fun testRequiredReviewersPresent() {
        val config = ReviewerConfig(
            requiredReviewers = listOf("tech-lead", "senior-dev"),
            requiredTeams = emptyList(),
            excludedReviewers = emptyList()
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "tech-lead", name = "Tech Lead"),
                User(username = "senior-dev", name = "Senior Dev")
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when all required reviewers present")
    }

    @Test
    fun testRequiredReviewersMissing() {
        val config = ReviewerConfig(
            requiredReviewers = listOf("tech-lead", "security-team"),
            requiredTeams = emptyList(),
            excludedReviewers = emptyList()
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "junior-dev", name = "Junior Dev")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("reviewer", findings[0].rule)
        assertTrue(findings[0].message.contains("Missing required reviewers"))
        assertTrue(findings[0].message.contains("tech-lead"))
        assertTrue(findings[0].message.contains("security-team"))
    }

    @Test
    fun testPartialRequiredReviewers() {
        val config = ReviewerConfig(
            requiredReviewers = listOf("tech-lead", "security-team", "qa-lead"),
            requiredTeams = emptyList(),
            excludedReviewers = emptyList()
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "tech-lead", name = "Tech Lead"),
                User(username = "junior-dev", name = "Junior Dev")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("security-team"))
        assertTrue(findings[0].message.contains("qa-lead"))
    }

    @Test
    fun testNoRequiredReviewers() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = emptyList(),
            excludedReviewers = emptyList()
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = emptyList()
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when no reviewers required")
    }

    @Test
    fun testRequiredTeamPresentExactMode() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = listOf("qa"),
            excludedReviewers = emptyList(),
            teamMatchMode = ReviewerConfig.TEAM_MATCH_EXACT
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "qa", name = "QA User")
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Exact match for team 'qa' with reviewer 'qa' should pass")
    }

    @Test
    fun testExactModeDoesNotMatchSubstring() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = listOf("qa"),
            excludedReviewers = emptyList(),
            teamMatchMode = ReviewerConfig.TEAM_MATCH_EXACT
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "jquaid", name = "John Quaid")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size, "Exact mode: team 'qa' should NOT match reviewer 'jquaid'")
    }

    @Test
    fun testContainsModeMatchesSubstring() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = listOf("security"),
            excludedReviewers = emptyList(),
            teamMatchMode = ReviewerConfig.TEAM_MATCH_CONTAINS
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "security-team-member", name = "Security Team Member")
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Contains mode should match substring")
    }

    @Test
    fun testRequiredTeamMissing() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = listOf("security", "backend"),
            excludedReviewers = emptyList()
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "frontend-dev", name = "Frontend Dev")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("reviewer", findings[0].rule)
        assertTrue(findings[0].message.contains("Missing reviewer from required teams"))
        assertTrue(findings[0].message.contains("security"))
        assertTrue(findings[0].message.contains("backend"))
    }

    @Test
    fun testTeamMatchingCaseInsensitiveExactMode() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = listOf("Security"),
            excludedReviewers = emptyList(),
            teamMatchMode = ReviewerConfig.TEAM_MATCH_EXACT
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "security", name = "Security Reviewer")
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Exact mode should be case-insensitive")
    }

    @Test
    fun testExcludedReviewersAbsent() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = emptyList(),
            excludedReviewers = listOf("bot-user", "automated-system")
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "human-reviewer", name = "Human Reviewer")
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when excluded reviewers are absent")
    }

    @Test
    fun testExcludedReviewersPresent() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = emptyList(),
            excludedReviewers = listOf("bot-user", "automated-system")
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "bot-user", name = "Bot User"),
                User(username = "human-reviewer", name = "Human Reviewer")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("reviewer", findings[0].rule)
        assertTrue(findings[0].message.contains("Excluded reviewers present"))
        assertTrue(findings[0].message.contains("bot-user"))
    }

    @Test
    fun testMultipleExcludedReviewersPresent() {
        val config = ReviewerConfig(
            requiredReviewers = emptyList(),
            requiredTeams = emptyList(),
            excludedReviewers = listOf("bot-user", "automated-system", "deprecated-user")
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(
            reviewers = listOf(
                User(username = "bot-user", name = "Bot User"),
                User(username = "automated-system", name = "Automated System"),
                User(username = "human-reviewer", name = "Human Reviewer")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("bot-user"))
        assertTrue(findings[0].message.contains("automated-system"))
    }

    @Test
    fun testCombinedRules() {
        val config = ReviewerConfig(
            requiredReviewers = listOf("tech-lead"),
            requiredTeams = listOf("security"),
            excludedReviewers = listOf("bot-user")
        )
        val validator = ReviewerValidator(config)

        val mrValid = createTestMergeRequest(
            reviewers = listOf(
                User(username = "tech-lead", name = "Tech Lead"),
                User(username = "security", name = "Security Member")
            )
        )

        val mrMissingRequired = createTestMergeRequest(
            reviewers = listOf(
                User(username = "security", name = "Security Member")
            )
        )

        val mrMissingTeam = createTestMergeRequest(
            reviewers = listOf(
                User(username = "tech-lead", name = "Tech Lead")
            )
        )

        val mrWithExcluded = createTestMergeRequest(
            reviewers = listOf(
                User(username = "tech-lead", name = "Tech Lead"),
                User(username = "security", name = "Security Member"),
                User(username = "bot-user", name = "Bot User")
            )
        )

        val findingsValid = validator.validate(mrValid)
        assertTrue(findingsValid.isEmpty())

        val findingsMissingRequired = validator.validate(mrMissingRequired)
        assertEquals(1, findingsMissingRequired.size)
        assertTrue(findingsMissingRequired[0].message.contains("tech-lead"))

        val findingsMissingTeam = validator.validate(mrMissingTeam)
        assertEquals(1, findingsMissingTeam.size)
        assertTrue(findingsMissingTeam[0].message.contains("security"))

        val findingsWithExcluded = validator.validate(mrWithExcluded)
        assertEquals(1, findingsWithExcluded.size)
        assertTrue(findingsWithExcluded[0].message.contains("bot-user"))
    }

    @Test
    fun testEmptyReviewersList() {
        val config = ReviewerConfig(
            requiredReviewers = listOf("tech-lead"),
            requiredTeams = emptyList(),
            excludedReviewers = emptyList()
        )
        val validator = ReviewerValidator(config)

        val mr = createTestMergeRequest(reviewers = emptyList())

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Missing required reviewers"))
    }
}
