package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMessagesValidatorTest {
    @Test
    fun testEmptyCommitListProducesNoFindings() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(commits = emptyList())

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testValidConventionalCommitPasses() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feat: add new feature")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testFormatAnyAndCustomAreKnownFormats() {
        val mr = createTestMergeRequest(commits = listOf(createCommit(message = "random message")))

        assertTrue(CommitMessagesValidator(CommitMessagesConfig(format = "any")).validate(mr).isEmpty())
        assertTrue(CommitMessagesValidator(CommitMessagesConfig(format = "custom", pattern = "^random .+")).validate(mr).isEmpty())
        val unknown = CommitMessagesValidator(CommitMessagesConfig(format = "bogus")).validate(mr)
        assertTrue(unknown.any { it.message.contains("Unknown commit format") })
    }

    @Test
    fun testInvalidConventionalCommitFails() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "invalid commit message")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("commitMessages", findings[0].rule)
        assertTrue(findings[0].message.contains("does not follow conventional format"))
    }

    @Test
    fun testEmptyCommitMessageFails() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("commitMessages", findings[0].rule)
    }

    @Test
    fun testConventionalCommitMissingColonFails() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feat add feature")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
    }

    @Test
    fun testBreakingChangeMarkerPasses() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feat!: breaking change")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCommitWithScopePasses() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "fix(scope): bug fix")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testScopeWithSpecialCharactersPasses() {
        val config = CommitMessagesConfig(format = "conventional")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feat(my-scope_v2): test")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testMaxLengthExactlyAtBoundaryPasses() {
        val config = CommitMessagesConfig(maxLength = 50)
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "X".repeat(50))
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("chars (limit") }
        assertTrue(lengthFindings.isEmpty())
    }

    @Test
    fun testFirstLineExceedingMaxLengthProducesWarning() {
        val config = CommitMessagesConfig(maxLength = 50)
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "X".repeat(51))
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("chars (limit") }
        assertEquals(1, lengthFindings.size)
        assertEquals(Severity.WARNING, lengthFindings[0].severity)
    }

    @Test
    fun testMultilineMessageOnlyValidatesFirstLineLength() {
        val config = CommitMessagesConfig(maxLength = 50)
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "Short first line\n" + "X".repeat(100))
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("chars (limit") }
        assertTrue(lengthFindings.isEmpty())
    }

    @Test
    fun testPatternMatchingValidCommitPasses() {
        val config = CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Add feature")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testPatternMatchingInvalidCommitFails() {
        val config = CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "invalid commit without ticket")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("does not match required pattern"))
    }

    @Test
    fun testInvalidPatternProducesError() {
        val config = CommitMessagesConfig(pattern = "[invalid")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "any message")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun testNullPatternSkipsPatternCheck() {
        val config = CommitMessagesConfig(pattern = null)
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "any message without pattern")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    // --- Custom conventional types and unknown format tests ---

    @Test
    fun testCustomConventionalTypes() {
        val config = CommitMessagesConfig(
            format = "conventional",
            conventionalTypes = listOf("feature", "bugfix", "hotfix"),
        )
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feature: add new thing")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Custom type 'feature' should pass: $findings")
    }

    @Test
    fun testCustomConventionalTypesRejectsDefaultTypes() {
        val config = CommitMessagesConfig(
            format = "conventional",
            conventionalTypes = listOf("feature", "bugfix"),
        )
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feat: add new thing")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("does not follow conventional format") },
            "Default type 'feat' should fail when custom types override"
        )
    }

    @Test
    fun testUnknownFormatProducesError() {
        val config = CommitMessagesConfig(format = "unknown-format")
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "any message")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("Unknown commit format: unknown-format") },
            "Unknown format should produce error: $findings"
        )
    }

    @Test
    fun testPatternAndFormatBothApplied() {
        val config = CommitMessagesConfig(
            format = "conventional",
            pattern = "^feat:.*"
        )
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "fix: some fix")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("does not match required pattern"))
    }

    @Test
    fun testMaliciousConventionalTypesAreEscaped() {
        val config = CommitMessagesConfig(
            format = "conventional",
            conventionalTypes = listOf("feat", "(a+)+", "fix")
        )
        val validator = CommitMessagesValidator(config)
        val commit = createCommit(message = "feat: normal commit")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Valid commit with escaped types should pass")
    }

    @Test
    fun testMetacharacterTypesDoNotCauseRegexInjection() {
        val config = CommitMessagesConfig(
            format = "conventional",
            conventionalTypes = listOf("feat|fix", ".*")
        )
        val validator = CommitMessagesValidator(config)
        // This should NOT match because types are escaped: "feat|fix" is literal, not alternation
        val commit = createCommit(message = "feat: should not match escaped pipe")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size, "Escaped types should not match as regex alternation")
    }

    private fun createCommit(
        sha: String = "abc1234",
        message: String = "test commit"
    ): Commit =
        Commit(
            sha = sha,
            message = message,
            author = CommitAuthor("user", "user@example.com"),
            timestamp = "2024-01-01T00:00:00Z"
        )
}
