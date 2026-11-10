package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossEntityConsistencyValidatorTest {
    @Test
    fun testCommitTasksSubsetOfMrPasses() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Feature work",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Part 1"),
                createCommit(sha = "def5678", message = "[MAPS-456][ios] Part 2"),
            ),
        )

        val findings = validator.validate(mr)
        val subsetFindings = findings.filter { it.message.contains("which is not in the MR title") && !it.message.contains("CHANGELOG") }
        assertTrue(subsetFindings.isEmpty())
    }

    @Test
    fun testCommitTaskNotInMrFails() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature work",
            commits = listOf(
                createCommit(message = "[MAPS-123][MAPS-999][ios] Sneaky extra task"),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.severity == Severity.ERROR && it.message.contains("MAPS-999") })
    }

    @Test
    fun testChangelogConsistencyPasses() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Feature work",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Part 1\n\nCHANGELOG: [MAPS-123] Task one."),
                createCommit(sha = "def5678", message = "[MAPS-456][ios] Part 2\n\nCHANGELOG: [MAPS-456] Task two."),
            ),
        )

        val findings = validator.validate(mr)
        val changelogFindings = findings.filter { it.message.contains("CHANGELOG") }
        assertTrue(changelogFindings.isEmpty())
    }

    @Test
    fun testChangelogExceedsMrTaskCountFails() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Single task MR",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Work\n\nCHANGELOG: [MAPS-123] Task one, [MAPS-456] Task two."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("exceeds") || it.message.contains("CHANGELOG mentions") })
    }

    @Test
    fun testEmptyCommitListProducesNoFindings() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature",
            commits = emptyList(),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testInvalidTaskPatternProducesError() {
        val config = CrossEntityConsistencyConfig(taskIdPattern = "[invalid")
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123] Title")

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid regex pattern") })
    }

    @Test
    fun testCustomTaskIdPattern() {
        val config = CrossEntityConsistencyConfig(taskIdPattern = "PROJ-\\d+")
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-100][ios] Feature",
            commits = listOf(
                createCommit(message = "[PROJ-100][ios] Work"),
            ),
        )

        val findings = validator.validate(mr)
        val subsetFindings = findings.filter { it.message.contains(" mentions ") }
        assertTrue(subsetFindings.isEmpty())
    }

    // --- Multi-source extraction tests ---

    @Test
    fun testExtractTaskIdsFromDescription() {
        val config = CrossEntityConsistencyConfig(mrTaskSource = "description")
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[ios] Feature work",
            description = "TASK: MAPS-123, MAPS-456",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Part 1"),
            ),
        )

        val findings = validator.validate(mr)
        val subsetFindings = findings.filter { it.message.contains(" mentions ") && !it.message.contains("CHANGELOG") }
        assertTrue(subsetFindings.isEmpty(), "Task from description should be recognized: $subsetFindings")
    }

    @Test
    fun testExtractTaskIdsFromBoth() {
        val config = CrossEntityConsistencyConfig(mrTaskSource = "both")
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature work",
            description = "TASK: MAPS-456",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Part 1"),
                createCommit(sha = "def5678", message = "[MAPS-456][ios] Part 2"),
            ),
        )

        val findings = validator.validate(mr)
        val subsetFindings = findings.filter { it.message.contains(" mentions ") && !it.message.contains("CHANGELOG") }
        assertTrue(subsetFindings.isEmpty(), "Tasks from title+description should be recognized: $subsetFindings")
    }

    @Test
    fun testGenericJiraPatternMatchesAnyProject() {
        val config = CrossEntityConsistencyConfig() // default is now [A-Z]+-\\d+
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-100][ios] Feature",
            commits = listOf(
                createCommit(message = "[PROJ-100][ios] Work"),
            ),
        )

        val findings = validator.validate(mr)
        val subsetFindings = findings.filter { it.message.contains(" mentions ") }
        assertTrue(subsetFindings.isEmpty(), "Generic pattern should match any project key: $subsetFindings")
    }

    @Test
    fun testChangelogDuplicateTaskIdsFails() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Feature work",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Part 1\n\nCHANGELOG: [MAPS-123] Task one."),
                createCommit(sha = "def5678", message = "[MAPS-456][ios] Part 2\n\nCHANGELOG: [MAPS-123] Duplicate entry."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("Duplicate task IDs found in CHANGELOG") && it.message.contains("MAPS-123") },
            "Should detect duplicate MAPS-123 in CHANGELOG: $findings"
        )
    }

    @Test
    fun testChangelogDuplicateTaskIdsInSameCommitFails() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Feature work",
            commits = listOf(
                createCommit(
                    message = "[MAPS-123][ios] Work\n\nCHANGELOG: [MAPS-123] First.\nCHANGELOG: [MAPS-123] Second."
                ),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("Duplicate task IDs found in CHANGELOG") && it.message.contains("MAPS-123") },
            "Should detect duplicate MAPS-123 within same commit CHANGELOG: $findings"
        )
    }

    @Test
    fun testChangelogNoDuplicatesPasses() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Feature work",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Part 1\n\nCHANGELOG: [MAPS-123] Task one."),
                createCommit(sha = "def5678", message = "[MAPS-456][ios] Part 2\n\nCHANGELOG: [MAPS-456] Task two."),
            ),
        )

        val findings = validator.validate(mr)
        val duplicateFindings = findings.filter { it.message.contains("Duplicate task IDs") }
        assertTrue(duplicateFindings.isEmpty(), "Should not detect duplicates: $duplicateFindings")
    }

    @Test
    fun testChangelogDuplicateDetectionDisabled() {
        val config = CrossEntityConsistencyConfig(changelogDuplicateDetection = false)
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature work",
            commits = listOf(
                createCommit(message = "[MAPS-123][ios] Work\n\nCHANGELOG: [MAPS-123] First."),
                createCommit(sha = "def5678", message = "[MAPS-123][ios] More\n\nCHANGELOG: [MAPS-123] Second."),
            ),
        )

        val findings = validator.validate(mr)
        val duplicateFindings = findings.filter { it.message.contains("Duplicate task IDs") }
        assertTrue(duplicateFindings.isEmpty(), "Duplicate detection disabled, should produce no findings: $duplicateFindings")
    }

    @Test
    fun testDisabledChecksProduceNoFindings() {
        val config = CrossEntityConsistencyConfig(
            commitTasksSubsetOfMr = false,
            changelogConsistency = false,
            changelogDuplicateDetection = false,
        )
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature",
            commits = listOf(
                createCommit(message = "[MAPS-999][ios] Different task\n\nCHANGELOG: [MAPS-888] Unknown task."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testChangelogDuplicateTaskIdsDifferentTextPassesWhenFlagEnabled() {
        val config = CrossEntityConsistencyConfig(
            changelogDuplicateRequiresMatchingText = true,
            changelogConsistency = false,
            commitTasksSubsetOfMr = false,
        )
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-6887][ios] Feature work",
            commits = listOf(
                createCommit(sha = "aaa0001", message = "[MAPS-6887] Part 1\n\nCHANGELOG: [MAPS-6887] Add JNI import file listener mutex."),
                createCommit(
                    sha = "aaa0002",
                    message = "[MAPS-6887] Part 2\n\nCHANGELOG: [MAPS-6887] Fix CollapsingToolbar Crashlytics issue."
                ),
                createCommit(sha = "aaa0003", message = "[MAPS-6887] Part 3\n\nCHANGELOG: [MAPS-6887] Fix FB analytics crashlytics issue."),
                createCommit(
                    sha = "aaa0004",
                    message = "[MAPS-6887] Part 4\n\nCHANGELOG: [MAPS-6887] Fix null pointer in MapView initialization."
                ),
                createCommit(sha = "aaa0005", message = "[MAPS-6887] Part 5\n\nCHANGELOG: [MAPS-6887] Update dependency to resolve ANR."),
            ),
        )

        val findings = validator.validate(mr)
        val duplicateFindings = findings.filter { it.message.contains("Duplicate task IDs found in CHANGELOG") }
        assertTrue(duplicateFindings.isEmpty(), "Different descriptions for same task ID should not be flagged: $duplicateFindings")
    }

    @Test
    fun testChangelogIdenticalDuplicateTextFailsWhenFlagEnabled() {
        val config = CrossEntityConsistencyConfig(
            changelogDuplicateRequiresMatchingText = true,
            changelogConsistency = false,
            commitTasksSubsetOfMr = false,
        )
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature work",
            commits = listOf(
                createCommit(sha = "bbb0001", message = "[MAPS-123] Part 1\n\nCHANGELOG: [MAPS-123] Fix crash on startup."),
                createCommit(sha = "bbb0002", message = "[MAPS-123] Part 2\n\nCHANGELOG: [MAPS-123] Fix crash on startup."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("Duplicate task IDs found in CHANGELOG") && it.message.contains("MAPS-123") },
            "Identical CHANGELOG lines for same task ID should be flagged: $findings"
        )
    }

    @Test
    fun testChangelogDuplicateRequiresMatchingTextDefaultBehaviorUnchanged() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Feature work",
            commits = listOf(
                createCommit(sha = "ccc0001", message = "[MAPS-123] Part 1\n\nCHANGELOG: [MAPS-123] Fix crash on startup."),
                createCommit(sha = "ccc0002", message = "[MAPS-123] Part 2\n\nCHANGELOG: [MAPS-123] Fix another issue."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("Duplicate task IDs found in CHANGELOG") && it.message.contains("MAPS-123") },
            "Default config should still flag same task ID across commits even with different text: $findings"
        )
    }

    @Test
    fun `changelogCoverage off does not flag missing tickets`() {
        val config = CrossEntityConsistencyConfig(changelogCoverage = false)
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Combined feature",
            commits = listOf(
                createCommit(message = "[MAPS-123][MAPS-456][ios] Wire up\n\nCHANGELOG: [MAPS-123] Only first task."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.none { it.message.contains("not covered by any CHANGELOG entry") },
            "Coverage check should be off by default: $findings"
        )
    }

    @Test
    fun `changelogCoverage on flags tickets without CHANGELOG entry`() {
        val config = CrossEntityConsistencyConfig(changelogCoverage = true)
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Combined feature",
            commits = listOf(
                createCommit(message = "[MAPS-123][MAPS-456][ios] Wire up\n\nCHANGELOG: [MAPS-123] Only first task."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any {
                it.severity == Severity.ERROR &&
                    it.message.contains("not covered by any CHANGELOG entry") &&
                    it.message.contains("MAPS-456")
            },
            "Coverage check should flag MAPS-456: $findings"
        )
    }

    @Test
    fun `changelogCoverage on passes when all MR tickets have CHANGELOG entries`() {
        val config = CrossEntityConsistencyConfig(changelogCoverage = true)
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][MAPS-456][ios] Combined feature",
            commits = listOf(
                createCommit(
                    message = "[MAPS-123][MAPS-456][ios] Wire up\n\n" +
                        "CHANGELOG: [MAPS-123] First task.\n" +
                        "CHANGELOG: [MAPS-456] Second task.",
                ),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.none { it.message.contains("not covered by any CHANGELOG entry") },
            "All MR tickets covered should pass: $findings"
        )
    }

    @Test
    fun `changelogCoverage on emits both extra and missing findings together`() {
        val config = CrossEntityConsistencyConfig(changelogCoverage = true)
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-1][MAPS-2][ios] Combined",
            commits = listOf(
                createCommit(message = "[MAPS-1][ios] Part 1\n\nCHANGELOG: [MAPS-1] First."),
                createCommit(sha = "ddd0001", message = "[MAPS-2][ios] Part 2\n\nCHANGELOG: [MAPS-3] Wrong ticket."),
            ),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("CHANGELOG mentions") && it.message.contains("MAPS-3") },
            "Should flag MAPS-3 as extra: $findings"
        )
        assertTrue(
            findings.any { it.message.contains("not covered by any CHANGELOG entry") && it.message.contains("MAPS-2") },
            "Should flag MAPS-2 as missing coverage: $findings"
        )
    }

    @Test
    fun `CHANGELOG tasks not in the MR title are counted with a short list`() {
        val validator = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig(changelogConsistency = true))
        val body = (1..5).joinToString("\n") { "CHANGELOG: [ALPHA-$it] Change $it." }
        val mr = createTestMergeRequest(title = "Integration", commits = listOf(createCommit(message = "Merge\n\n$body")))
        val finding = validator.validate(mr).single { it.message.startsWith("CHANGELOG") }
        assertEquals("CHANGELOG mentions 5 tasks not in the MR title (ALPHA-1, ALPHA-2, ALPHA-3, +2 more)", finding.message)
    }

    @Test
    fun `commits with tasks missing from the MR title collapse into one finding`() {
        val validator = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig())
        val commits = (1..4).map { createCommit(sha = "aaaa00$it", message = "[ALPHA-$it] Work") }
        val finding = validator.validate(createTestMergeRequest(title = "Integration", commits = commits)).single()
        assertEquals("4 commits mention tasks not in the MR title (aaaa001, aaaa002, aaaa003, +1 more)", finding.message)
        assertEquals("Add [ALPHA-1] to the MR title or remove it from the commit subject.", finding.suggestion)
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
