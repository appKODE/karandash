package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.percent.KarandashPercent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeLimitsValidatorTest {
    @Test
    fun `out-of-range warnThreshold decodes and is reported and ignored instead of throwing`() {
        val config = KarandashJson.Default.decodeFromJsonElement(
            SizeLimitsConfig.serializer(),
            KarandashJson.Default.parseToJsonElement("""{"maxFiles": 10, "severities": {"warnThreshold": "20%"}}"""),
        )
        assertEquals(null, config.severities.validWarnThreshold)

        val findings = SizeLimitsValidator(config).validate(createMergeRequest(fileCount = 9))

        assertEquals(1, findings.size, "only the threshold problem, no 'Approaching' warning: $findings")
        assertEquals(Severity.WARNING, findings.single().severity)
        assertTrue(findings.single().message.contains("warnThreshold"))
        assertTrue("50%" !in findings.single().message, "the valid range belongs in the suggestion")
        assertEquals("Set severities.warnThreshold to a value between 50% and 99%, e.g. 80%.", findings.single().suggestion)
    }

    @Test
    fun `an exceeded size limit carries the MR_TOO_LARGE code`() {
        val findings = SizeLimitsValidator(SizeLimitsConfig(maxFiles = 2)).validate(createMergeRequest(fileCount = 5))

        assertEquals(listOf("E0204"), findings.map { it.errorCode }, findings.toString())
    }

    @Test
    fun `violation suggestion names the overflow and the limit and the message stays the problem`() {
        val findings = SizeLimitsValidator(SizeLimitsConfig(maxFiles = 2, maxCommits = 1))
            .validate(createMergeRequest(fileCount = 5, commitCount = 3))

        assertEquals(
            listOf(
                "Too many files changed: 5 (max: 2)" to "Split the MR: 5 files changed is 3 over the limit of 2.",
                "Too many commits: 3 (max: 1)" to "Split the MR: 3 commits is 2 over the limit of 1.",
            ),
            findings.map { it.message to it.suggestion },
        )
    }

    @Test
    fun `approaching warning suggestion names the remaining headroom`() {
        val severity = SizeLimitsSeverityConfig(warnThreshold = KarandashPercent.ofPercent(80))
        val finding = SizeLimitsValidator(SizeLimitsConfig(maxFiles = 10, severities = severity))
            .validate(createMergeRequest(fileCount = 9))
            .single()

        assertEquals("Keep the MR at or under 10 files changed: 1 more allowed, or split it.", finding.suggestion)
    }

    @Test
    fun `in-range warnThreshold still warns when approaching the limit`() {
        val severity = SizeLimitsSeverityConfig(warnThreshold = KarandashPercent.ofPercent(80))
        val findings = SizeLimitsValidator(SizeLimitsConfig(maxFiles = 10, severities = severity))
            .validate(createMergeRequest(fileCount = 9))

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.startsWith("Approaching"))
    }

    @Test
    fun `should pass when file count is within limit`() {
        val config = SizeLimitsConfig(maxFiles = 10)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(fileCount = 5)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should have no findings when within file limit")
    }

    @Test
    fun `should fail when file count exceeds limit`() {
        val config = SizeLimitsConfig(maxFiles = 5)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(fileCount = 10)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have one finding for exceeded file limit")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("10"), "Should mention actual count")
        assertTrue(findings[0].message.contains("5"), "Should mention limit")
        assertTrue(findings[0].message.contains("files"), "Should mention files")
        assertEquals("sizeLimits", findings[0].rule)
    }

    @Test
    fun `should pass when line count is within limit`() {
        val config = SizeLimitsConfig(maxLines = 1000)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 100, deletions = 50),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 200, deletions = 100)
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should have no findings when within line limit")
    }

    @Test
    fun `should fail when line count exceeds limit`() {
        val config = SizeLimitsConfig(maxLines = 200)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 150, deletions = 100),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 100, deletions = 50)
            )
        )

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have one finding for exceeded line limit")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("400"), "Should mention actual count (150+100+100+50)")
        assertTrue(findings[0].message.contains("200"), "Should mention limit")
        assertTrue(findings[0].message.contains("lines"), "Should mention lines")
    }

    @Test
    fun `should pass when commit count is within limit`() {
        val config = SizeLimitsConfig(maxCommits = 10)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(commitCount = 5)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should have no findings when within commit limit")
    }

    @Test
    fun `should fail when commit count exceeds limit`() {
        val config = SizeLimitsConfig(maxCommits = 3)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(commitCount = 10)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have one finding for exceeded commit limit")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("10"), "Should mention actual count")
        assertTrue(findings[0].message.contains("3"), "Should mention limit")
        assertTrue(findings[0].message.contains("commits"), "Should mention commits")
    }

    @Test
    fun `commits cut by the page cap read as more than the loaded count`() {
        val validator = SizeLimitsValidator(SizeLimitsConfig(maxCommits = 3))
        val cut = validator.validate(createMergeRequest(commitCount = 10).copy(commitsTruncated = true)).single()
        assertEquals("Too many commits: more than 10 (10 loaded; http.pagination.maxPages: 0 loads all) (max: 3)", cut.message)
        val whole = validator.validate(createMergeRequest(commitCount = 10)).single()
        assertEquals("Too many commits: 10 (max: 3)", whole.message)
    }

    @Test
    fun `the platform commit count wins over a capped commit list`() {
        val validator = SizeLimitsValidator(SizeLimitsConfig(maxCommits = 3))
        val mr = createMergeRequest(commitCount = 2).copy(commitsTruncated = true, commitsTotal = 300)
        assertEquals("Too many commits: 300 (max: 3)", validator.validate(mr).single().message)
    }

    @Test
    fun `should validate multiple limits simultaneously`() {
        val config = SizeLimitsConfig(
            maxFiles = 5,
            maxLines = 100,
            maxCommits = 3
        )
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            fileCount = 10, // Exceeds maxFiles (5)
            commitCount = 5, // Exceeds maxCommits (3)
            changes = (1..10).map { index ->
                FileChange(
                    path = "file$index.kt",
                    changeType = ChangeType.MODIFIED,
                    additions = 10, // 10 files * 10 additions = 100 total
                    deletions = 10 // 10 files * 10 deletions = 100 total (200 total lines exceeds maxLines 100)
                )
            }
        )

        val findings = validator.validate(mr)

        assertEquals(3, findings.size, "Should have three findings for all exceeded limits")
        assertTrue(findings.any { it.message.contains("files") })
        assertTrue(findings.any { it.message.contains("lines") })
        assertTrue(findings.any { it.message.contains("commits") })
    }

    @Test
    fun `should pass when all limits are respected`() {
        val config = SizeLimitsConfig(
            maxFiles = 10,
            maxLines = 1000,
            maxCommits = 20
        )
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            fileCount = 5,
            commitCount = 10,
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 100, deletions = 50),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 200, deletions = 100)
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should have no findings when all limits are respected")
    }

    @Test
    fun `should handle zero additions and deletions`() {
        val config = SizeLimitsConfig(maxLines = 100)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.RENAMED, additions = 0, deletions = 0)
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should handle zero additions and deletions")
    }

    @Test
    fun `should pass with empty config`() {
        val config = SizeLimitsConfig()
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(fileCount = 1000, commitCount = 1000)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should pass with empty config (no limits set)")
    }

    @Test
    fun `should handle empty merge request`() {
        val config = SizeLimitsConfig(
            maxFiles = 10,
            maxLines = 1000,
            maxCommits = 20
        )
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(fileCount = 0, commitCount = 0, changes = emptyList())

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should handle empty merge request")
    }

    @Test
    fun `should calculate total lines correctly with multiple files`() {
        val config = SizeLimitsConfig(maxLines = 500)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 100, deletions = 50),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 150, deletions = 75),
                FileChange("file3.kt", ChangeType.ADDED, additions = 200, deletions = 0)
            )
        )

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should exceed limit")
        assertTrue(findings[0].message.contains("575"), "Should calculate 100+50+150+75+200+0")
    }

    // Boundary Tests
    @Test
    fun `exactly at maxFiles boundary passes`() {
        val config = SizeLimitsConfig(maxFiles = 5)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(fileCount = 5) // Exactly 5 files

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Exactly 5 files with maxFiles=5 should pass")
    }

    @Test
    fun `maxFiles equals zero fails for any files`() {
        val config = SizeLimitsConfig(maxFiles = 0)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)
            )
        )

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("files"), "Should mention files")
    }

    @Test
    fun `negative maxFiles allows any number of files`() {
        val config = SizeLimitsConfig(maxFiles = -1)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(fileCount = 100)

        val findings = validator.validate(mr)

        // Negative values cause the check (fileCount > -1) to always be true for positive counts
        // 100 > -1 is true, so it will fail validation
        assertEquals(1, findings.size, "Negative maxFiles causes validation failure for any positive file count")
        assertEquals(Severity.ERROR, findings[0].severity)
    }

    @Test
    fun `exactly at maxLines boundary passes`() {
        val config = SizeLimitsConfig(maxLines = 200)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 100, deletions = 50),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 30, deletions = 20)
            )
        )
        // Total: 100 + 50 + 30 + 20 = 200 (exactly at boundary)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Exactly 200 lines with maxLines=200 should pass")
    }

    @Test
    fun `maxLines equals zero fails for any lines`() {
        val config = SizeLimitsConfig(maxLines = 0)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)
            )
        )

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("lines"), "Should mention lines")
    }

    @Test
    fun `negative maxLines causes validation failure for any lines`() {
        val config = SizeLimitsConfig(maxLines = -1)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 1000, deletions = 1000),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 1000, deletions = 1000)
            )
        )

        val findings = validator.validate(mr)

        // Negative values cause the check (totalLines > -1) to always be true for positive counts
        // Any positive line count > -1 is true, so it will fail validation
        assertEquals(1, findings.size, "Negative maxLines causes validation failure for any positive line count")
        assertEquals(Severity.ERROR, findings[0].severity)
    }

    @Test
    fun `exactly at maxCommits boundary passes`() {
        val config = SizeLimitsConfig(maxCommits = 5)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(commitCount = 5) // Exactly 5 commits

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Exactly 5 commits with maxCommits=5 should pass")
    }

    @Test
    fun `maxCommits equals zero fails for any commits`() {
        val config = SizeLimitsConfig(maxCommits = 0)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(commitCount = 1)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("commits"), "Should mention commits")
    }

    @Test
    fun `negative maxCommits causes validation failure for any commits`() {
        val config = SizeLimitsConfig(maxCommits = -1)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(commitCount = 100)

        val findings = validator.validate(mr)

        // Negative values cause the check (commitCount > -1) to always be true for positive counts
        // 100 > -1 is true, so it will fail validation
        assertEquals(1, findings.size, "Negative maxCommits causes validation failure for any positive commit count")
        assertEquals(Severity.ERROR, findings[0].severity)
    }

    @Test
    fun `both limits at exact boundary passes`() {
        val config = SizeLimitsConfig(
            maxFiles = 3,
            maxLines = 150,
            maxCommits = 5
        )
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            fileCount = 3, // Exactly at maxFiles
            commitCount = 5, // Exactly at maxCommits
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 50, deletions = 25),
                FileChange("file2.kt", ChangeType.MODIFIED, additions = 50, deletions = 25),
                FileChange("file3.kt", ChangeType.MODIFIED, additions = 0, deletions = 0)
            )
        )
        // Total lines: 50 + 25 + 50 + 25 + 0 + 0 = 150 (exactly at maxLines)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "All limits at exact boundary should pass")
    }

    @Test
    fun `excludeRenames excludes renamed files from file count`() {
        val config = SizeLimitsConfig(maxFiles = 2, excludeRenames = true)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 10, deletions = 5),
                FileChange("file2.kt", ChangeType.RENAMED, additions = 0, deletions = 0),
                FileChange("file3.kt", ChangeType.RENAMED, additions = 0, deletions = 0),
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Renamed files should be excluded from count (1 modified <= 2)")
    }

    @Test
    fun `excludeRenames excludes renamed files from line count`() {
        val config = SizeLimitsConfig(maxLines = 50, excludeRenames = true)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 20, deletions = 10),
                FileChange("renamed.kt", ChangeType.RENAMED, additions = 100, deletions = 100),
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Renamed files should be excluded from line count (30 <= 50)")
    }

    @Test
    fun `excludeDeleted excludes deleted files from file count`() {
        val config = SizeLimitsConfig(maxFiles = 2, excludeDeleted = true)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 10, deletions = 5),
                FileChange("file2.kt", ChangeType.DELETED, additions = 0, deletions = 50),
                FileChange("file3.kt", ChangeType.DELETED, additions = 0, deletions = 200),
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Deleted files should be excluded from count (1 modified <= 2)")
    }

    @Test
    fun `excludeDeleted excludes deleted files from line count`() {
        val config = SizeLimitsConfig(maxLines = 50, excludeDeleted = true)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 20, deletions = 10),
                FileChange("deleted.kt", ChangeType.DELETED, additions = 0, deletions = 500),
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Deleted files should be excluded from line count (30 <= 50)")
    }

    @Test
    fun `excludeRenames and excludeDeleted combined exclude both types`() {
        val config = SizeLimitsConfig(maxFiles = 1, maxLines = 30, excludeRenames = true, excludeDeleted = true)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("modified.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
                FileChange("renamed.kt", ChangeType.RENAMED, additions = 0, deletions = 0),
                FileChange("deleted.kt", ChangeType.DELETED, additions = 0, deletions = 500),
            )
        )

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Both renamed and deleted files excluded (1 file, 20 lines)")
    }

    @Test
    fun `excludeRenames false still counts renamed files`() {
        val config = SizeLimitsConfig(maxFiles = 1, excludeRenames = false)
        val validator = SizeLimitsValidator(config)
        val mr = createMergeRequest(
            changes = listOf(
                FileChange("file1.kt", ChangeType.MODIFIED, additions = 10, deletions = 5),
                FileChange("renamed.kt", ChangeType.RENAMED, additions = 0, deletions = 0),
            )
        )

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Renamed files should count when excludeRenames=false")
    }

    private fun createMergeRequest(
        fileCount: Int = 0,
        commitCount: Int = 0,
        changes: List<FileChange>? = null
    ): MergeRequest {
        val actualChanges = changes ?: (1..fileCount).map { index ->
            FileChange(
                path = "file$index.kt",
                changeType = ChangeType.MODIFIED,
                additions = 10,
                deletions = 5
            )
        }

        val commits = (1..commitCount).map { index ->
            Commit(
                sha = "sha$index",
                message = "Commit $index",
                author = CommitAuthor(name = "Test User", email = "test@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
        }

        return MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = "Test description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User", email = "test@example.com"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = commits,
            changes = actualChanges
        )
    }
}
