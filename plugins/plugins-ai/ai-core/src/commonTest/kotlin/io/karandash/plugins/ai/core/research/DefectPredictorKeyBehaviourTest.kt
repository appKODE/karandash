package io.karandash.plugins.ai.core.research

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.percent.KarandashPercent
import io.karandash.plugins.ai.core.config.DefectPredictorConfig
import io.karandash.plugins.ai.core.config.DefectPredictorThresholdsConfig
import io.karandash.plugins.ai.core.config.DefectPredictorWeightsConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** One MR with fixed factor scores: size 0.2, diffusion 0.05, history 0.3, purpose 1.0, review 0.8. */
class DefectPredictorKeyBehaviourTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test",
        description = null,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = listOf(commit(), commit()),
        changes = listOf(FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, additions = 100, deletions = 0)),
    )

    private fun commit() =
        Commit(
            sha = "abc123",
            message = "short",
            author = CommitAuthor("Dev", "dev@test.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )

    private fun pct(p: Int) = KarandashPercent.ofPercent(p)

    private fun onlyWeight(
        size: Int = 0,
        diffusion: Int = 0,
        history: Int = 0,
        purpose: Int = 0,
        experience: Int = 0,
        review: Int = 0,
    ) = DefectPredictorConfig(
        weights = DefectPredictorWeightsConfig(
            size = pct(size),
            diffusion = pct(diffusion),
            history = pct(history),
            purpose = pct(purpose),
            experience = pct(experience),
            review = pct(review),
        ),
    )

    private fun probability(config: DefectPredictorConfig) = JitDefectPredictor.predict(mr, config).probability

    private fun thresholds(t: DefectPredictorThresholdsConfig) = JitDefectPredictor.predict(mr, DefectPredictorConfig(thresholds = t))

    @Test
    fun `defectPredictor weights size alone makes the probability the size factor`() {
        assertEquals(0.2, probability(onlyWeight(size = 100)), 1e-9)
    }

    @Test
    fun `defectPredictor weights diffusion alone makes the probability the diffusion factor`() {
        assertEquals(0.05, probability(onlyWeight(diffusion = 100)), 1e-9)
    }

    @Test
    fun `defectPredictor weights history alone makes the probability the history factor`() {
        assertEquals(0.3, probability(onlyWeight(history = 100)), 1e-9)
    }

    @Test
    fun `defectPredictor weights purpose alone makes the probability the purpose factor`() {
        assertEquals(1.0, probability(onlyWeight(purpose = 100)), 1e-9)
    }

    @Test
    fun `defectPredictor weights experience alone makes the probability the missing description factor`() {
        assertEquals(0.8, probability(onlyWeight(experience = 100)), 1e-9)
    }

    @Test
    fun `defectPredictor weights review alone makes the probability the inverse description factor`() {
        assertEquals(0.2, probability(onlyWeight(review = 100)), 1e-9)
    }

    @Test
    fun `defectPredictor thresholds mediumRisk lowered turns the default low risk into medium`() {
        assertEquals(DefectRiskLevel.LOW, JitDefectPredictor.predict(mr).riskLevel)
        val result = thresholds(DefectPredictorThresholdsConfig(mediumRisk = pct(30)))
        assertEquals(DefectRiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun `defectPredictor thresholds highRisk lowered turns the default low risk into high`() {
        val result = thresholds(DefectPredictorThresholdsConfig(highRisk = pct(30), mediumRisk = pct(20)))
        assertEquals(DefectRiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `defectPredictor thresholds largeMrLines sets the line count that saturates the size factor`() {
        val result = thresholds(DefectPredictorThresholdsConfig(largeMrLines = 100))
        assertEquals(1.0, result.factors.getValue("size"), 1e-9)
    }

    @Test
    fun `defectPredictor thresholds manyFilesCount sets the file count that saturates the diffusion factor`() {
        val result = thresholds(DefectPredictorThresholdsConfig(manyFilesCount = 1))
        assertEquals(1.0, result.factors.getValue("diffusion"), 1e-9)
    }

    @Test
    fun `defectPredictor thresholds shortMessageChars below the commit length clears the purpose factor`() {
        val result = thresholds(DefectPredictorThresholdsConfig(shortMessageChars = 3))
        assertEquals(0.0, result.factors.getValue("purpose"), 1e-9)
    }
}
