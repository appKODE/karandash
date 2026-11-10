package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pin: commit-body `required` and `requiredWhenSubjectMatches` are live and covered here. */
class CommitBodyValidatorTest {
    @Test
    fun testPatternMatchPasses() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+", required = true)
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Add feature\n\nCHANGELOG: [MAPS-123] Add new feature.")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testPatternMismatchFails() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+", required = true)
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Add feature\n\nSome random body text")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals(
            "Commit abc1234: no CHANGELOG line in the body; first line \"Some random body text\" does not match " +
                "(checked: first line only, match-mode: fullMatch; set match-mode: anyLine to check every line)",
            findings[0].message,
        )
        assertTrue(findings[0].suggestion.orEmpty().startsWith("Add a line like \"CHANGELOG: [MAPS-"), findings[0].suggestion)
    }

    @Test
    fun `anyLine mismatch counts the lines and quotes the closest one`() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+", required = true, matchMode = "anyLine")
        val commit = createCommit(message = "[MAPS-1] Feature\n\nRefactor the parser\nChangelog - new parser\nSee docs")
        val finding = CommitBodyValidator(config).validate(createTestMergeRequest(commits = listOf(commit))).single()
        assertEquals(
            "Commit abc1234: no CHANGELOG line in the body; none of 3 lines match (match-mode: anyLine); " +
                "closest: \"Changelog - new parser\"",
            finding.message,
        )
    }

    @Test
    fun `anyLine mismatch on a one line body quotes that line`() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+", required = true, matchMode = "anyLine")
        val commit = createCommit(message = "[MAPS-1] Feature\n\nRefactor the parser")
        val finding = CommitBodyValidator(config).validate(createTestMergeRequest(commits = listOf(commit))).single()
        assertTrue(finding.message.endsWith("its only line \"Refactor the parser\" does not match (match-mode: anyLine)"), finding.message)
    }

    @Test
    fun `invalid match mode is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { CommitBodyConfig(matchMode = "regex") }
        assertTrue(error.message.orEmpty().startsWith("Invalid matchMode 'regex'"), error.message)
    }

    @Test
    fun `pattern without a leading word is named in the message`() {
        val config = CommitBodyConfig(pattern = "(Closes|Fixes) #\\d+", required = true, matchMode = "contains")
        val commit = createCommit(message = "Fix\n\nSome text")
        val finding = CommitBodyValidator(config).validate(createTestMergeRequest(commits = listOf(commit))).single()
        assertTrue(finding.message.startsWith("Commit abc1234: no line matching `(Closes|Fixes) #\\d+` in the body; "), finding.message)
        assertTrue(finding.message.contains("match-mode: contains"), finding.message)
    }

    @Test
    fun `body of a commit the subject filter leaves out is not checked against the pattern`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenSubjectMatches = "\\[MAPS-\\d+\\]",
        )
        val commit = createCommit(message = "Bump dependencies\n\nRoutine update of libraries")
        val findings = CommitBodyValidator(config).validate(createTestMergeRequest(title = "Bump deps", commits = listOf(commit)))
        assertTrue(findings.isEmpty(), "$findings")
    }

    @Test
    fun `body of a commit the subject filter requires is still checked`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenSubjectMatches = "\\[MAPS-\\d+\\]",
        )
        val commit = createCommit(message = "[MAPS-1] Feature\n\nSome text")
        assertEquals(1, CommitBodyValidator(config).validate(createTestMergeRequest(commits = listOf(commit))).size)
    }

    @Test
    fun `without filters an optional body that is present must still match`() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: .+")
        val commit = createCommit(message = "Fix\n\nSome text")
        assertEquals(1, CommitBodyValidator(config).validate(createTestMergeRequest(commits = listOf(commit))).size)
    }

    @Test
    fun `mismatches across many commits collapse into one finding`() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: .+", required = true)
        val commits = (1..5).map { createCommit(sha = "c0ffee$it", message = "Fix $it\n\nno changelog $it") }
        val finding = CommitBodyValidator(config).validate(createTestMergeRequest(commits = commits)).single()
        assertEquals("5 commits have no CHANGELOG line in the body (c0ffee1, c0ffee2, c0ffee3, +2 more)", finding.message)
        assertEquals(
            5,
            finding.attachments
                .single()
                .content
                .lines()
                .size
        )
    }

    @Test
    fun testRequiredButMissingFails() {
        val config = CommitBodyConfig(required = true)
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Title only")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("body is required but missing"))
    }

    @Test
    fun testNotRequiredAndMissingPasses() {
        val config = CommitBodyConfig(pattern = "^CHANGELOG: .+", required = false)
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Title only")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testContainsMode() {
        val config = CommitBodyConfig(pattern = "CHANGELOG", required = true, matchMode = "contains")
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Feature\n\nSome prefix CHANGELOG: [MAPS-123] task.")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Contains mode should match substring: $findings")
    }

    @Test
    fun testAnyLineMode() {
        val config = CommitBodyConfig(pattern = "CHANGELOG", required = true, matchMode = "anyLine")
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Feature\n\nFirst line no match\nCHANGELOG: [MAPS-123] task.")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "AnyLine mode should match second body line: $findings")
    }

    @Test
    fun testInvalidPatternProducesError() {
        val config = CommitBodyConfig(pattern = "[invalid", required = true)
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-123][ios] Feature\n\nCHANGELOG: [MAPS-123] task.")
        val mr = createTestMergeRequest(commits = listOf(commit))

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid regex pattern") })
    }

    @Test
    fun `requiredWhenTitleMatches triggers when MR title has ticket but commit subject does not`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenTitleMatches = "\\[MAPS-\\d+\\]",
        )
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[android] Migrate to new logic")
        val mr = createTestMergeRequest(
            title = "[MAPS-123][android] Migrate to new logic",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("body is required but missing"))
    }

    @Test
    fun `requiredWhenTitleMatches does not trigger when MR title has no ticket`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenTitleMatches = "\\[MAPS-\\d+\\]",
        )
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[android] Quick fix")
        val mr = createTestMergeRequest(
            title = "[android] Quick fix",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `requiredWhenTitleMatches passes when commit has valid changelog`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenTitleMatches = "\\[MAPS-\\d+\\]",
        )
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[android] Migrate\n\nCHANGELOG: [MAPS-123] Migrate to new logic.")
        val mr = createTestMergeRequest(
            title = "[MAPS-123][android] Migrate to new logic",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `subject filter still works when title does not match`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenSubjectMatches = "\\[MAPS-\\d+\\]",
            requiredWhenTitleMatches = "\\[MAPS-\\d+\\]",
        )
        val validator = CommitBodyValidator(config)
        val commit = createCommit(message = "[MAPS-456][android] Fix bug")
        val mr = createTestMergeRequest(
            title = "[android] Minor fixes",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("body is required but missing"))
    }

    @Test
    fun `anyCommit scope passes when at least one of multiple commits has matching body`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenSubjectMatches = "\\[MAPS-\\d+\\]",
            matchMode = "anyLine",
            scope = CommitBodyConfig.SCOPE_ANY_COMMIT,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][android] Combined feature",
            commits = listOf(
                createCommit(sha = "aaaa1234", message = "[MAPS-123][android] Refactor\n\nTASK:https://example.com/MAPS-123"),
                createCommit(sha = "bbbb5678", message = "[MAPS-456][android] Implement\n\nTASK:https://example.com/MAPS-456"),
                createCommit(
                    sha = "cccc9999",
                    message = "[MAPS-123][MAPS-456][android] Wire up\n\n" +
                        "CHANGELOG: [MAPS-123] First piece\n" +
                        "CHANGELOG: [MAPS-456] Second piece",
                ),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "anyCommit should pass when one commit body matches: $findings")
    }

    @Test
    fun `anyCommit scope emits one MR-level finding when no commits match`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenSubjectMatches = "\\[MAPS-\\d+\\]",
            matchMode = "anyLine",
            scope = CommitBodyConfig.SCOPE_ANY_COMMIT,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Add feature",
            commits = listOf(
                createCommit(sha = "aaaa1234", message = "[MAPS-123][android] Refactor\n\nTASK:https://example.com/MAPS-123"),
                createCommit(sha = "bbbb5678", message = "[MAPS-123][android] Implement\n\nTASK:https://example.com/MAPS-123"),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("No commit body in this MR"))
    }

    @Test
    fun `anyCommit scope emits no findings when no commits are required`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: \\[MAPS-\\d+\\] .+",
            requiredWhenSubjectMatches = "\\[MAPS-\\d+\\]",
            matchMode = "anyLine",
            scope = CommitBodyConfig.SCOPE_ANY_COMMIT,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            title = "Fix typos in docs",
            commits = listOf(
                createCommit(sha = "aaaa1234", message = "Quick fix"),
                createCommit(sha = "bbbb5678", message = "Another typo"),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `anyCommit scope surfaces compile error once`() {
        val config = CommitBodyConfig(
            pattern = "[invalid",
            required = true,
            scope = CommitBodyConfig.SCOPE_ANY_COMMIT,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit(sha = "aaaa1234", message = "[MAPS-123][android] Feature\n\nCHANGELOG: [MAPS-123] task."),
                createCommit(sha = "bbbb5678", message = "[MAPS-456][android] Feature\n\nCHANGELOG: [MAPS-456] task."),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun `invalid scope value throws on construction`() {
        var threw = false
        try {
            CommitBodyConfig(scope = "everyOtherCommit")
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message?.contains("Invalid scope") == true)
        }
        assertTrue(threw, "Expected IllegalArgumentException for invalid scope")
    }

    private fun createCommit(
        sha: String = "abc1234",
        message: String = "test commit",
    ): Commit =
        Commit(
            sha = sha,
            message = message,
            author = CommitAuthor("user", "user@example.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )
}
