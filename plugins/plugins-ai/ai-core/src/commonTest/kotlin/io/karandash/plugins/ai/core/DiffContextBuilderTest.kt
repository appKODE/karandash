package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffContextBuilderTest {
    private fun makeMr(
        title: String = "Fix login bug",
        description: String? = "Fixes the login timeout issue",
        changes: List<FileChange> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 42,
            title = title,
            description = description,
            sourceBranch = "fix/login",
            targetBranch = "main",
            author = User(username = "jdoe", name = "Jane Doe"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(
                Commit(
                    sha = "abc123",
                    message = "Fix timeout",
                    author = CommitAuthor(name = "Jane Doe", email = "jane@example.com"),
                    timestamp = "2026-01-15T10:00:00Z",
                ),
            ),
            changes = changes,
        )

    private fun makeChange(
        path: String,
        additions: Int = 5,
        deletions: Int = 2,
        diffContent: String? = "+ added line\n- removed line",
        changeType: ChangeType = ChangeType.MODIFIED,
    ): FileChange =
        FileChange(
            path = path,
            changeType = changeType,
            additions = additions,
            deletions = deletions,
            diffContent = diffContent,
        )

    @Test
    fun buildWithNormalMrContainsMetadata() {
        val mr = makeMr(changes = listOf(makeChange("src/Login.kt")))
        val result = DiffContextBuilder.build(mr, AiRuleConfig())

        assertTrue(result.metadata.contains("Fix login bug"), "metadata should contain title")
        assertTrue(result.metadata.contains("Jane Doe"), "metadata should contain author name")
        assertTrue(result.metadata.contains("fix/login"), "metadata should contain source branch")
        assertTrue(result.metadata.contains("main"), "metadata should contain target branch")
        assertTrue(result.metadata.contains("src/Login.kt"), "metadata should contain file path")
    }

    @Test
    fun buildWithEmptyChangesProducesEmptyDiffs() {
        val mr = makeMr(changes = emptyList())
        val result = DiffContextBuilder.build(mr, AiRuleConfig())

        assertTrue(result.preparedDiff.isEmpty(), "preparedDiff should be empty for no changes")
        assertTrue(result.fullDiff.isEmpty(), "fullDiff should be empty for no changes")
        assertEquals(0, result.omittedFileCount)
        assertEquals(0, result.fullDiffCharCount)
    }

    @Test
    fun buildRespectsCharLimits() {
        val largeDiff = "a".repeat(5000)
        val changes = listOf(
            makeChange("file1.kt", diffContent = largeDiff),
            makeChange("file2.kt", diffContent = largeDiff),
        )
        val mr = makeMr(changes = changes)
        val config = AiRuleConfig(maxDiffCharsTotal = 6000, maxDiffCharsPerFile = 3000)

        val result = DiffContextBuilder.build(mr, config)

        assertTrue(
            result.preparedDiff.length < largeDiff.length * 2,
            "preparedDiff should be truncated below total of both diffs",
        )
        assertEquals(10_000, result.fullDiffCharCount, "fullDiffCharCount should sum all raw diffs")
    }

    @Test
    fun omittedFileCountIsCorrect() {
        val changes = listOf(
            makeChange("file1.kt", diffContent = "a".repeat(15_000)),
            makeChange("file2.kt", diffContent = "b".repeat(15_000)),
        )
        val mr = makeMr(changes = changes)
        val config = AiRuleConfig(maxDiffCharsTotal = 16_000, maxDiffCharsPerFile = 16_000)

        val result = DiffContextBuilder.build(mr, config)

        assertTrue(result.omittedFileCount >= 1, "at least one file should be omitted")
    }

    @Test
    fun preprocessorExclusionsAreLabelledApartFromBudgetOmissions() {
        val changes = listOf(
            makeChange("file1.kt", diffContent = "a".repeat(15_000)),
            makeChange("file2.kt", diffContent = "b".repeat(15_000)),
            makeChange("gone.kt", diffContent = "-removed", changeType = ChangeType.DELETED),
        )
        val mr = makeMr(changes = changes)
        val config = AiRuleConfig(
            maxDiffCharsTotal = 16_000,
            maxDiffCharsPerFile = 16_000,
            diffPreprocessor = DiffPreprocessorOptions(skipDeletedFiles = true),
        )

        val result = DiffContextBuilder.build(mr, config)

        assertEquals(listOf("file2.kt"), DiffContextBuilder.omittedPaths(mr, result.preparedDiff, preprocessor = config.diffPreprocessor))
        assertEquals(1, result.omittedFileCount)
        assertEquals(mapOf("gone.kt" to "deleted"), DiffContextBuilder.excludedPaths(mr, config.diffPreprocessor))
    }

    @Test
    fun deletedFileKeptByThePreprocessorIsNotExcluded() {
        val mr = makeMr(changes = listOf(makeChange("gone.kt", diffContent = "-removed", changeType = ChangeType.DELETED)))
        val options = DiffPreprocessorOptions(skipDeletedFiles = false)

        assertEquals(emptyMap(), DiffContextBuilder.excludedPaths(mr, options))
    }

    @Test
    fun fullDiffContainsAllFileContent() {
        val changes = listOf(
            makeChange("a.kt", diffContent = "diff-a"),
            makeChange("b.kt", diffContent = "diff-b"),
        )
        val mr = makeMr(changes = changes)
        val result = DiffContextBuilder.build(mr, AiRuleConfig())

        assertTrue(result.fullDiff.contains("diff-a"), "fullDiff should contain first file diff")
        assertTrue(result.fullDiff.contains("diff-b"), "fullDiff should contain second file diff")
        assertTrue(result.fullDiff.contains("### a.kt"), "fullDiff should contain file path header")
    }

    @Test
    fun buildWithNullDiffContentSkipsFile() {
        val changes = listOf(
            makeChange("a.kt", diffContent = null),
            makeChange("b.kt", diffContent = "some diff"),
        )
        val mr = makeMr(changes = changes)
        val result = DiffContextBuilder.build(mr, AiRuleConfig())

        assertEquals(9, result.fullDiffCharCount, "only non-null diffs count toward char total")
    }
}
