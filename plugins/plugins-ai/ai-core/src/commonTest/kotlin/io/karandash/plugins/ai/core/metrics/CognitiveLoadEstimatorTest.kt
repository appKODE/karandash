package io.karandash.plugins.ai.core.metrics

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveLoadEstimatorTest {
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
    fun `small MR under 200 LOC has LOW risk`() {
        val changes = listOf(makeChange("src/A.kt", additions = 50, deletions = 30))
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertEquals(CognitiveRiskLevel.LOW, metrics.riskLevel)
    }

    @Test
    fun `medium MR between 200 and 400 LOC has MEDIUM risk`() {
        val changes = listOf(makeChange("src/A.kt", additions = 180, deletions = 60))
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertEquals(CognitiveRiskLevel.MEDIUM, metrics.riskLevel)
    }

    @Test
    fun `large MR over 400 LOC has HIGH risk`() {
        val changes = listOf(makeChange("src/A.kt", additions = 300, deletions = 150))
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertEquals(CognitiveRiskLevel.HIGH, metrics.riskLevel)
    }

    @Test
    fun `review time estimated from effective LOC`() {
        // 150 LOC single dir → effectiveLoc=150, estimatedMinutes=150/7.5=20
        val changes = listOf(makeChange("src/A.kt", additions = 100, deletions = 50))
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertTrue(metrics.estimatedReviewMinutes > 0.0, "Review time must be positive")
        assertEquals(false, metrics.exceedsRecommendedTime)
    }

    @Test
    fun `recommendation generated for HIGH risk MR`() {
        val changes = listOf(makeChange("src/A.kt", additions = 300, deletions = 150))
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertNotNull(metrics.recommendation, "HIGH risk should have a recommendation")
        assertTrue(
            metrics.recommendation!!.contains("400-LOC threshold"),
            "Recommendation should mention threshold, got: ${metrics.recommendation}",
        )
    }

    @Test
    fun `no recommendation for small MR`() {
        val changes = listOf(makeChange("src/A.kt", additions = 30, deletions = 10))
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertNull(metrics.recommendation, "Small MR should have no recommendation")
    }

    @Test
    fun `concept diversity counts distinct file extensions`() {
        val changes = listOf(
            makeChange("src/A.kt", additions = 10, deletions = 0),
            makeChange("src/B.kt", additions = 10, deletions = 0),
            makeChange("test/C.java", additions = 10, deletions = 0),
            makeChange("docs/D.md", additions = 10, deletions = 0),
        )
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertEquals(3, metrics.conceptDiversity, "Should count 3 distinct extensions: kt, java, md")
    }

    @Test
    fun `directory count reflects distinct directories`() {
        val changes = listOf(
            makeChange("src/A.kt"),
            makeChange("src/B.kt"),
            makeChange("test/C.kt"),
            makeChange("docs/D.md"),
        )
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        assertEquals(3, metrics.directoryCount)
    }

    @Test
    fun `directory diversity increases effective LOC`() {
        val singleDir = listOf(
            makeChange("src/A.kt", additions = 50, deletions = 0),
            makeChange("src/B.kt", additions = 50, deletions = 0),
        )
        val multiDir = listOf(
            makeChange("src/A.kt", additions = 50, deletions = 0),
            makeChange("lib/B.kt", additions = 50, deletions = 0),
        )
        val singleMetrics = CognitiveLoadEstimator.estimate(makeMr(singleDir))
        val multiMetrics = CognitiveLoadEstimator.estimate(makeMr(multiDir))
        assertTrue(
            multiMetrics.effectiveLoc >= singleMetrics.effectiveLoc,
            "Multi-directory MR should have higher effectiveLoc",
        )
    }

    @Test
    fun `empty MR has LOW risk and minimum review time`() {
        val metrics = CognitiveLoadEstimator.estimate(makeMr())
        assertEquals(CognitiveRiskLevel.LOW, metrics.riskLevel)
        assertTrue(metrics.estimatedReviewMinutes >= 1.0, "Minimum review time should be 1 minute")
    }

    @Test
    fun `large MR touching many files gets recommendation`() {
        val changes = (1..12).map { makeChange("src/File$it.kt", additions = 20, deletions = 5) }
        val metrics = CognitiveLoadEstimator.estimate(makeMr(changes))
        // 12 files * 25 LOC = 300 LOC → MEDIUM risk, file count > 10 → recommendation
        assertNotNull(metrics.recommendation)
    }
}
