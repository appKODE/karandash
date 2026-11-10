package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.core.config.ProcessRiskFactors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessRiskCalculatorTest {
    private fun makeMr(
        changes: List<FileChange> = emptyList(),
        commits: List<Commit> = emptyList(),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test",
        description = null,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = commits,
        changes = changes,
    )

    private fun makeChange(
        path: String,
        additions: Int = 5,
        deletions: Int = 2,
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = additions,
        deletions = deletions,
        diffContent = null,
    )

    private fun makeCommit(sha: String = "abc") =
        Commit(
            sha = sha,
            message = "commit",
            author = CommitAuthor("Dev", "dev@test.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )

    @Test
    fun `empty MR has low composite risk`() {
        val mr = makeMr()
        val metrics = ProcessRiskCalculator.calculate(mr)
        assertTrue(metrics.composite < 0.6, "Empty MR should have low risk, got ${metrics.composite}")
    }

    @Test
    fun `high churn ratio detected when deletions equal additions`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/A.kt", additions = 50, deletions = 50)),
        )
        val metrics = ProcessRiskCalculator.calculate(mr)
        assertTrue(metrics.churnRatio > 0.5, "Equal add/delete should produce high churn, got ${metrics.churnRatio}")
    }

    @Test
    fun `low churn ratio for pure additions`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/A.kt", additions = 100, deletions = 0)),
        )
        val metrics = ProcessRiskCalculator.calculate(mr)
        assertTrue(metrics.churnRatio <= 0.5, "Pure additions should produce low churn, got ${metrics.churnRatio}")
    }

    @Test
    fun `file diversity increases with more directories`() {
        val single = makeMr(
            changes = listOf(
                makeChange("src/A.kt"),
                makeChange("src/B.kt"),
            ),
        )
        val spread = makeMr(
            changes = listOf(
                makeChange("src/A.kt"),
                makeChange("lib/B.kt"),
                makeChange("test/C.kt"),
                makeChange("docs/D.md"),
                makeChange("scripts/E.sh"),
            ),
        )
        val singleMetrics = ProcessRiskCalculator.calculate(single)
        val spreadMetrics = ProcessRiskCalculator.calculate(spread)
        assertTrue(
            spreadMetrics.fileDiversity > singleMetrics.fileDiversity,
            "More directories should give higher diversity: ${spreadMetrics.fileDiversity} vs ${singleMetrics.fileDiversity}",
        )
    }

    @Test
    fun `single directory has zero file diversity`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/A.kt"), makeChange("src/B.kt"), makeChange("src/C.kt")),
        )
        val metrics = ProcessRiskCalculator.calculate(mr)
        assertTrue(metrics.fileDiversity == 0.0, "Single directory should have 0.0 diversity, got ${metrics.fileDiversity}")
    }

    @Test
    fun `commit density calculation - many files few commits is higher risk`() {
        val fewCommitsMany = makeMr(
            changes = listOf(makeChange("A.kt"), makeChange("B.kt"), makeChange("C.kt"), makeChange("D.kt")),
            commits = listOf(makeCommit("a")),
        )
        val manyCommitsFew = makeMr(
            changes = listOf(makeChange("A.kt")),
            commits = listOf(makeCommit("a"), makeCommit("b"), makeCommit("c"), makeCommit("d")),
        )
        val fewMetrics = ProcessRiskCalculator.calculate(fewCommitsMany)
        val manyMetrics = ProcessRiskCalculator.calculate(manyCommitsFew)
        assertTrue(
            fewMetrics.commitDensity > manyMetrics.commitDensity,
            "Few commits / many files should have higher density risk: ${fewMetrics.commitDensity} vs ${manyMetrics.commitDensity}",
        )
    }

    @Test
    fun `author familiarity reduces risk when files are known`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/A.kt"), makeChange("src/B.kt")),
        )
        val knownHistory = MrHistory(
            authorFileTouchCounts = mapOf("src/A.kt" to 5, "src/B.kt" to 3),
        )
        val unknownHistory = MrHistory(
            authorFileTouchCounts = emptyMap(),
        )
        val knownMetrics = ProcessRiskCalculator.calculate(mr, knownHistory)
        val unknownMetrics = ProcessRiskCalculator.calculate(mr, unknownHistory)
        assertTrue(
            knownMetrics.authorFamiliarity < unknownMetrics.authorFamiliarity,
            "Known files should produce lower familiarity risk: ${knownMetrics.authorFamiliarity} vs ${unknownMetrics.authorFamiliarity}",
        )
    }

    @Test
    fun `fully familiar author has zero familiarity risk`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/A.kt"), makeChange("src/B.kt")),
        )
        val history = MrHistory(
            authorFileTouchCounts = mapOf("src/A.kt" to 10, "src/B.kt" to 7),
        )
        val metrics = ProcessRiskCalculator.calculate(mr, history)
        assertTrue(metrics.authorFamiliarity == 0.0, "Fully familiar author should have 0.0 risk, got ${metrics.authorFamiliarity}")
    }

    @Test
    fun `composite score is bounded between 0 and 1`() {
        val extremeHighMr = makeMr(
            changes = (1..20).map { makeChange("dir$it/file$it.kt", additions = 100, deletions = 100) },
            commits = listOf(makeCommit("a")),
        )
        val extremeLowMr = makeMr(
            changes = listOf(makeChange("src/A.kt", additions = 10, deletions = 0)),
            commits = (1..10).map { makeCommit("sha$it") },
        )
        val highMetrics = ProcessRiskCalculator.calculate(extremeHighMr)
        val lowMetrics = ProcessRiskCalculator.calculate(extremeLowMr)
        assertTrue(highMetrics.composite in 0.0..1.0, "Composite must be in [0,1], got ${highMetrics.composite}")
        assertTrue(lowMetrics.composite in 0.0..1.0, "Composite must be in [0,1], got ${lowMetrics.composite}")
    }

    @Test
    fun `all individual metrics are bounded between 0 and 1`() {
        val mr = makeMr(
            changes = (1..15).map { makeChange("dir$it/file$it.kt", additions = 50, deletions = 50) },
            commits = listOf(makeCommit("a"), makeCommit("b")),
        )
        val history = MrHistory(authorFileTouchCounts = mapOf("dir1/file1.kt" to 3))
        val metrics = ProcessRiskCalculator.calculate(mr, history)
        assertTrue(metrics.churnRatio in 0.0..1.0, "churnRatio out of bounds: ${metrics.churnRatio}")
        assertTrue(metrics.fileDiversity in 0.0..1.0, "fileDiversity out of bounds: ${metrics.fileDiversity}")
        assertTrue(metrics.commitDensity in 0.0..1.0, "commitDensity out of bounds: ${metrics.commitDensity}")
        assertTrue(metrics.authorFamiliarity in 0.0..1.0, "authorFamiliarity out of bounds: ${metrics.authorFamiliarity}")
        assertTrue(metrics.composite in 0.0..1.0, "composite out of bounds: ${metrics.composite}")
    }

    @Test
    fun `disabled factors are left out and the remaining weights rescaled`() {
        val mr = makeMr(
            changes = listOf(
                FileChange("a/A.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
                FileChange("b/B.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
            ),
        )
        val all = ProcessRiskCalculator.calculate(mr)
        val churnOnly = ProcessRiskCalculator.calculate(
            mr,
            factors = ProcessRiskFactors(
                fileDiversity = false,
                changeDensity = false,
                authorFamiliarity = false,
            ),
        )
        assertEquals(all.churnRatio, churnOnly.composite)
        val none = ProcessRiskCalculator.calculate(
            mr,
            factors = ProcessRiskFactors(false, false, false, false),
        )
        assertEquals(0.0, none.composite)
    }
}
