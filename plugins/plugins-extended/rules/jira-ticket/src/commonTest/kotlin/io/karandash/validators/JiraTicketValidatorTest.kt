package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraTicketValidatorTest {
    @Test
    fun testValidTicketFromCorrectProjectPasses() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            pattern = "([A-Z]+-\\d+)"
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testNoTicketInTitleProducesError() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            pattern = "([A-Z]+-\\d+)"
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "feature without ticket")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("jiraTicket", findings[0].rule)
        assertTrue(findings[0].message.contains("No JIRA ticket found"))
    }

    @Test
    fun testTicketFromWrongProjectProducesError() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ", "TEAM"),
            pattern = "([A-Z]+-\\d+)"
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "WRONG-123: feature")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("jiraTicket", findings[0].rule)
        assertTrue(findings[0].message.contains("Invalid JIRA project key"))
        assertTrue(findings[0].message.contains("WRONG"))
    }

    @Test
    fun testMultipleValidProjectKeys() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ", "TEAM", "WORK"),
            pattern = "([A-Z]+-\\d+)"
        )
        val validator = JiraTicketValidator(config)

        assertTrue(validator.validate(createTestMergeRequest(title = "PROJ-123: feature")).isEmpty())
        assertTrue(validator.validate(createTestMergeRequest(title = "TEAM-456: feature")).isEmpty())
        assertTrue(validator.validate(createTestMergeRequest(title = "WORK-789: feature")).isEmpty())
    }

    @Test
    fun testTicketInMiddleOfTitle() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            pattern = "([A-Z]+-\\d+)"
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "feat: PROJ-123 add feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCustomPattern() {
        val config = JiraTicketConfig(
            projectKeys = listOf("CUSTOM"),
            pattern = "(CUSTOM-[0-9]{4})"
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "CUSTOM-1234: feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testNullPatternUsesDefault() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            pattern = null
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testInvalidRegexPatternReturnsError() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            pattern = "[invalid(("
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("jiraTicket", findings[0].rule)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun testEmptyProjectKeysListAcceptsAnyTicket() {
        val config = JiraTicketConfig(
            projectKeys = emptyList(),
            pattern = ".*"
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "TEST-123 feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCheckInTitleFindsTicketInTitle() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_TITLE
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCheckInTitleMissesTicketInDescription() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_TITLE
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(
            title = "feature without ticket",
            description = "PROJ-123 is the ticket"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("No JIRA ticket found"))
    }

    @Test
    fun testCheckInDescriptionFindsTicketInDescription() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_DESCRIPTION
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(
            title = "feature without ticket",
            description = "Fixes PROJ-456"
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCheckInDescriptionMissesTicketInTitle() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_DESCRIPTION
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("No JIRA ticket found"))
    }

    @Test
    fun testCheckInBothFindsTitleFirst() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_BOTH
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCheckInBothFallsBackToDescription() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_BOTH
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(
            title = "feature without ticket",
            description = "Fixes PROJ-789"
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCheckInBothFailsWhenNeitherHasTicket() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            checkIn = JiraTicketConfig.CHECK_IN_BOTH
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(
            title = "feature without ticket",
            description = "no ticket here either"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("No JIRA ticket found"))
    }

    @Test
    fun testValidateStatusWithoutJiraUrlEmitsWarning() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            validateStatus = listOf("In Progress"),
            jiraUrl = null
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("jiraUrl is not set"))
        assertTrue(findings[0].message.contains("Status validation skipped"))
    }

    @Test
    fun testValidateAssigneeWithoutJiraUrlEmitsWarning() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            validateAssignee = true,
            jiraUrl = null
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("jiraUrl is not set"))
        assertTrue(findings[0].message.contains("Assignee validation skipped"))
    }

    @Test
    fun testNoWarningsWhenValidateStatusNotConfigured() {
        val config = JiraTicketConfig(
            projectKeys = listOf("PROJ"),
            validateStatus = null,
            validateAssignee = false
        )
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }
}
