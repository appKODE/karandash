package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.percent.KarandashPercent
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeLimitsKeyBehaviourTest {
    private fun change(
        path: String,
        type: ChangeType = ChangeType.MODIFIED,
        lines: Int = 10,
    ) = FileChange(path, type, additions = lines)

    private fun findings(
        config: SizeLimitsConfig,
        vararg changes: FileChange,
    ) = SizeLimitsValidator(config).validate(createTestMergeRequest(changes = changes.toList()))

    @Test
    fun `maxFiles flags an MR with too many files`() {
        val config = SizeLimitsConfig(maxFiles = 1)
        assertTrue(findings(config, change("a"), change("b")).single().message.contains("files changed"))
        assertEquals(emptyList(), findings(config, change("a")))
    }

    @Test
    fun `maxLines flags an MR with too many lines`() {
        val config = SizeLimitsConfig(maxLines = 15)
        assertTrue(findings(config, change("a"), change("b")).single().message.contains("lines changed"))
        assertEquals(emptyList(), findings(config, change("a")))
    }

    @Test
    fun `maxCommits flags an MR with too many commits`() {
        val mr = createTestMergeRequest(commits = listOf(Commit("a1", "m", CommitAuthor("u", "e"), "t")))
        assertTrue(
            SizeLimitsValidator(SizeLimitsConfig(maxCommits = 0))
                .validate(mr)
                .single()
                .message
                .contains("commits")
        )
        assertEquals(emptyList(), SizeLimitsValidator(SizeLimitsConfig(maxCommits = 1)).validate(mr))
    }

    @Test
    fun `excludeRenames leaves renamed files out of the count`() {
        val files = arrayOf(change("a"), change("b", ChangeType.RENAMED))
        assertEquals(1, findings(SizeLimitsConfig(maxFiles = 1), *files).size)
        assertEquals(emptyList(), findings(SizeLimitsConfig(maxFiles = 1, excludeRenames = true), *files))
    }

    @Test
    fun `excludeDeleted leaves deleted files out of the count`() {
        val files = arrayOf(change("a"), change("b", ChangeType.DELETED))
        assertEquals(1, findings(SizeLimitsConfig(maxFiles = 1), *files).size)
        assertEquals(emptyList(), findings(SizeLimitsConfig(maxFiles = 1, excludeDeleted = true), *files))
    }

    @Test
    fun `severity onViolation warning lowers the violation to a warning`() {
        val files = arrayOf(change("a"), change("b"))
        assertEquals(Severity.ERROR, findings(SizeLimitsConfig(maxFiles = 1), *files).single().severity)
        val config = SizeLimitsConfig(maxFiles = 1, severities = SizeLimitsSeverityConfig(onViolation = "warning"))
        assertEquals(Severity.WARNING, findings(config, *files).single().severity)
    }

    @Test
    fun `severity warnThreshold warns before the limit is reached`() {
        val config = SizeLimitsConfig(maxFiles = 4, severities = SizeLimitsSeverityConfig(warnThreshold = KarandashPercent.ofPercent(50)))
        val files = arrayOf(change("a"), change("b"), change("c"))
        assertTrue(findings(config, *files).single().message.contains("Approaching"))
        assertEquals(emptyList(), findings(SizeLimitsConfig(maxFiles = 4), *files))
    }
}
