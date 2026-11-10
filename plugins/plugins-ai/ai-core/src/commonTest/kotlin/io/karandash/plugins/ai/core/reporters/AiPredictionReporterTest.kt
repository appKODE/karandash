package io.karandash.plugins.ai.core.reporters

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiPredictionReporterTest {
    private val author = User(username = "dev", name = "Dev")

    private fun record(findingsByRule: Map<String, Int> = emptyMap()) =
        RunRecord(
            timestamp = 1_000_000L,
            mrId = null,
            totalFindings = findingsByRule.values.sum(),
            errorCount = 0,
            warningCount = 0,
            infoCount = 0,
            findingsByRule = findingsByRule,
        )

    private fun mr(changes: List<FileChange> = emptyList(),) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = author,
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes,
        )

    private fun change(
        additions: Int = 10,
        deletions: Int = 5
    ) = FileChange(
        path = "src/Foo.kt",
        changeType = ChangeType.MODIFIED,
        additions = additions,
        deletions = deletions,
    )

    @Test
    fun insufficientHistoryReturnsEmpty() {
        val history = List(9) { record(mapOf("rule-a" to 1)) }
        val result = AiPredictionReporter.predict(mr(), history)
        assertTrue(result.isEmpty())
    }

    @Test
    fun exactlyTenRecordsProducesPredictions() {
        val history = List(10) { record(mapOf("rule-a" to 1)) }
        val result = AiPredictionReporter.predict(mr(), history)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun highBaseRateMapsToHighConfidence() {
        // rule-a fires on all 10 runs → base rate 1.0 → HIGH confidence
        val history = List(10) { record(mapOf("rule-a" to 1)) }
        val result = AiPredictionReporter.predict(mr(), history)
        val pred = result.first { it.ruleId == "rule-a" }
        assertEquals(PredictionConfidence.HIGH, pred.confidence)
        assertTrue(pred.probability > 0.7)
    }

    @Test
    fun lowBaseRateMapsToLowConfidence() {
        // rule-rare fires on 2/10 runs → base rate 0.2 → LOW confidence
        val history = (0 until 10).map { i ->
            if (i < 2) record(mapOf("rule-rare" to 1)) else record()
        }
        val result = AiPredictionReporter.predict(mr(), history)
        val pred = result.first { it.ruleId == "rule-rare" }
        assertEquals(PredictionConfidence.LOW, pred.confidence)
    }

    @Test
    fun largeMrBoostsProbability() {
        // rule-a base rate = 0.3 (MEDIUM without boost)
        // MR with 600 LOC should push it above HIGH threshold via LARGE_MR_BOOST=1.5
        val history = (0 until 10).map { i ->
            if (i < 3) record(mapOf("rule-a" to 1)) else record()
        }
        val smallMr = mr(changes = listOf(change(additions = 10, deletions = 5)))
        val largeMr = mr(changes = listOf(change(additions = 550, deletions = 60)))

        val smallPred = AiPredictionReporter.predict(smallMr, history).first { it.ruleId == "rule-a" }
        val largePred = AiPredictionReporter.predict(largeMr, history).first { it.ruleId == "rule-a" }

        assertTrue(largePred.probability > smallPred.probability)
    }

    @Test
    fun manyFilesBoostsProbability() {
        // rule-a base rate = 0.3; MR with 15 files gets MANY_FILES_BOOST=1.3
        val history = (0 until 10).map { i ->
            if (i < 3) record(mapOf("rule-a" to 1)) else record()
        }
        val fewFilesMr = mr(changes = List(3) { change() })
        val manyFilesMr = mr(changes = List(15) { change() })

        val fewPred = AiPredictionReporter.predict(fewFilesMr, history).first { it.ruleId == "rule-a" }
        val manyPred = AiPredictionReporter.predict(manyFilesMr, history).first { it.ruleId == "rule-a" }

        assertTrue(manyPred.probability > fewPred.probability)
    }

    @Test
    fun probabilityClampedToOne() {
        // rule-a fires every run (1.0 base), large MR and many files would exceed 1.0 without clamping
        val history = List(10) { record(mapOf("rule-a" to 1)) }
        val bigMr = mr(changes = List(20) { change(additions = 100, deletions = 50) })
        val result = AiPredictionReporter.predict(bigMr, history)
        val pred = result.first { it.ruleId == "rule-a" }
        assertTrue(pred.probability <= 1.0)
    }

    @Test
    fun predictionsSortedByProbabilityDescending() {
        val history = (0 until 10).map { i ->
            val r = buildMap<String, Int> {
                if (i < 9) put("rule-high", 1) // 0.9 base rate
                if (i < 3) put("rule-low", 1) // 0.3 base rate
            }
            record(r)
        }
        val result = AiPredictionReporter.predict(mr(), history)
        val probs = result.map { it.probability }
        assertEquals(probs.sortedDescending(), probs)
    }

    @Test
    fun formatReportShowsInsufficientHistoryMessage() {
        val text = AiPredictionReporter.formatReport(emptyList())
        assertTrue(text.contains("Insufficient history"))
    }

    @Test
    fun formatReportProducesTable() {
        val preds = listOf(
            RulePrediction("rule-a", 0.8, PredictionConfidence.HIGH),
            RulePrediction("rule-b", 0.2, PredictionConfidence.LOW),
        )
        val text = AiPredictionReporter.formatReport(preds)
        assertTrue(text.contains("| Rule |"))
        assertTrue(text.contains("rule-a"))
        assertTrue(text.contains("HIGH"))
        assertTrue(text.contains("rule-b"))
        assertTrue(text.contains("LOW"))
    }
}
