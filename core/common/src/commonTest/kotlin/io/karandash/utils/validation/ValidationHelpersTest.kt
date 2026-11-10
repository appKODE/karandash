package io.karandash.utils.validation

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationHelpersTest {
    private fun createMR(changes: List<FileChange>) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User("testuser", "Test User"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes
        )

    private fun createFileChange(path: String) =
        FileChange(
            path = path,
            changeType = ChangeType.MODIFIED,
            additions = 10,
            deletions = 5
        )

    @Test
    fun testValidateFilesMatchingPattern() {
        val mr = createMR(
            listOf(
                createFileChange("src/main.kt"),
                createFileChange("src/utils.kt"),
                createFileChange("README.md"),
                createFileChange("build.gradle.kts")
            )
        )

        var callCount = 0
        val findings = mr.validateFiles(Regex("\\.kt$")) { file ->
            callCount++
            listOf(
                Finding(
                    severity = Severity.INFO,
                    message = "Kotlin file: ${file.path}",
                    rule = "test-rule"
                )
            )
        }

        assertEquals(2, callCount) // Only .kt files
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.message.contains(".kt") })
    }

    @Test
    fun testValidateFilesNoMatches() {
        val mr = createMR(
            listOf(
                createFileChange("README.md"),
                createFileChange("LICENSE")
            )
        )

        var callCount = 0
        val findings = mr.validateFiles(Regex("\\.kt$")) { file ->
            callCount++
            emptyList()
        }

        assertEquals(0, callCount)
        assertEquals(emptyList(), findings)
    }

    @Test
    fun testValidateFilesMultipleFindings() {
        val mr = createMR(
            listOf(
                createFileChange("test1.kt"),
                createFileChange("test2.kt")
            )
        )

        val findings = mr.validateFiles(Regex("\\.kt$")) { file ->
            listOf(
                Finding(Severity.WARNING, "Warning for ${file.path}", "rule1"),
                Finding(Severity.ERROR, "Error for ${file.path}", "rule2")
            )
        }

        assertEquals(4, findings.size) // 2 findings per file, 2 files
    }

    @Test
    fun testCheckRuleConditionTrue() {
        val findings = checkRule(
            rule = "test-rule",
            severity = Severity.ERROR,
            condition = true,
            message = "This should not appear"
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun testCheckRuleConditionFalse() {
        val findings = checkRule(
            rule = "test-rule",
            severity = Severity.ERROR,
            condition = false,
            message = "Condition failed"
        )

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("Condition failed", findings[0].message)
        assertEquals("test-rule", findings[0].rule)
    }

    @Test
    fun testCheckRuleWithExplanation() {
        val findings = checkRule(
            rule = "size-limit",
            severity = Severity.WARNING,
            condition = false,
            message = "MR is too large",
            explanation = "Consider splitting into smaller MRs"
        )

        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("MR is too large", findings[0].message)
        assertEquals("Consider splitting into smaller MRs", findings[0].explanation)
    }

    @Test
    fun testCheckRuleDifferentSeverities() {
        val error = checkRule("rule1", Severity.ERROR, false, "Error message")
        assertEquals(Severity.ERROR, error[0].severity)

        val warning = checkRule("rule2", Severity.WARNING, false, "Warning message")
        assertEquals(Severity.WARNING, warning[0].severity)

        val info = checkRule("rule3", Severity.INFO, false, "Info message")
        assertEquals(Severity.INFO, info[0].severity)
    }
}
