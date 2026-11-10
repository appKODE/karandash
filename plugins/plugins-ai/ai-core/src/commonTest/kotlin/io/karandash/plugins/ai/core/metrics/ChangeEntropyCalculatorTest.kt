package io.karandash.plugins.ai.core.metrics

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeEntropyCalculatorTest {
    private fun makeMr(changes: List<FileChange> = emptyList()) =
        MergeRequest(
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
            commits = emptyList(),
            changes = changes,
        )

    private fun makeChange(
        path: String,
        additions: Int = 10,
        deletions: Int = 5
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = additions,
        deletions = deletions,
        diffContent = null,
    )

    @Test
    fun `empty MR has zero entropy`() {
        val metrics = ChangeEntropyCalculator.calculate(makeMr())
        assertEquals(0.0, metrics.fileEntropy)
        assertEquals(0.0, metrics.directoryEntropy)
        assertEquals(0.0, metrics.normalizedEntropy)
    }

    @Test
    fun `single file has zero entropy`() {
        val metrics = ChangeEntropyCalculator.calculate(
            makeMr(listOf(makeChange("src/A.kt", additions = 50, deletions = 10))),
        )
        assertEquals(0.0, metrics.fileEntropy, 1e-10)
        assertEquals(0.0, metrics.normalizedEntropy, 1e-10)
    }

    @Test
    fun `uniform distribution has maximum normalized entropy`() {
        val changes = listOf(
            makeChange("src/A.kt", additions = 10, deletions = 0),
            makeChange("src/B.kt", additions = 10, deletions = 0),
            makeChange("src/C.kt", additions = 10, deletions = 0),
            makeChange("src/D.kt", additions = 10, deletions = 0),
        )
        val metrics = ChangeEntropyCalculator.calculate(makeMr(changes))
        assertTrue(
            abs(metrics.normalizedEntropy - 1.0) < 0.001,
            "Uniform distribution should have normalizedEntropy ~1.0, got ${metrics.normalizedEntropy}",
        )
    }

    @Test
    fun `skewed distribution has lower entropy than uniform`() {
        val uniform = listOf(
            makeChange("src/A.kt", additions = 10, deletions = 0),
            makeChange("src/B.kt", additions = 10, deletions = 0),
            makeChange("src/C.kt", additions = 10, deletions = 0),
        )
        val skewed = listOf(
            makeChange("src/A.kt", additions = 100, deletions = 0),
            makeChange("src/B.kt", additions = 1, deletions = 0),
            makeChange("src/C.kt", additions = 1, deletions = 0),
        )
        val uniformMetrics = ChangeEntropyCalculator.calculate(makeMr(uniform))
        val skewedMetrics = ChangeEntropyCalculator.calculate(makeMr(skewed))
        assertTrue(
            skewedMetrics.fileEntropy < uniformMetrics.fileEntropy,
            "Skewed distribution should have lower entropy: ${skewedMetrics.fileEntropy} vs ${uniformMetrics.fileEntropy}",
        )
    }

    @Test
    fun `directory entropy calculated from directory grouping`() {
        val changes = listOf(
            makeChange("src/A.kt", additions = 10, deletions = 0),
            makeChange("src/B.kt", additions = 10, deletions = 0),
            makeChange("test/C.kt", additions = 10, deletions = 0),
            makeChange("test/D.kt", additions = 10, deletions = 0),
        )
        val metrics = ChangeEntropyCalculator.calculate(makeMr(changes))
        // Two directories with equal changes - directory entropy should be ~1 bit
        assertTrue(metrics.directoryEntropy > 0.0, "Directory entropy should be positive for multiple dirs")
        assertEquals(2, metrics.directoryCount)
    }

    @Test
    fun `normalized entropy is bounded between 0 and 1`() {
        val changes = (1..10).map { makeChange("dir$it/file$it.kt", additions = it * 5, deletions = it) }
        val metrics = ChangeEntropyCalculator.calculate(makeMr(changes))
        assertTrue(metrics.normalizedEntropy in 0.0..1.0, "normalizedEntropy out of bounds: ${metrics.normalizedEntropy}")
    }

    @Test
    fun `shannonEntropy of equal values equals log2 of count`() {
        // 4 equal values: H = log2(4) = 2.0
        val entropy = ChangeEntropyCalculator.shannonEntropy(listOf(10, 10, 10, 10))
        assertTrue(
            abs(entropy - 2.0) < 0.001,
            "shannonEntropy of 4 equal values should be log2(4)=2.0, got $entropy",
        )
    }

    @Test
    fun `shannonEntropy of all-zero values is zero`() {
        assertEquals(0.0, ChangeEntropyCalculator.shannonEntropy(listOf(0, 0, 0)))
    }

    @Test
    fun `file count and directory count match MR`() {
        val changes = listOf(
            makeChange("a/X.kt"),
            makeChange("a/Y.kt"),
            makeChange("b/Z.kt"),
        )
        val metrics = ChangeEntropyCalculator.calculate(makeMr(changes))
        assertEquals(3, metrics.fileCount)
        assertEquals(2, metrics.directoryCount)
    }
}
