package io.karandash.plugins.ai.core.metrics

import io.karandash.percent.KarandashPercent
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.config.CalibrationConfig
import io.karandash.plugins.ai.core.config.CalibrationRuleTarget
import io.karandash.plugins.ai.core.config.EvtAutoThresholdConfig
import io.karandash.plugins.ai.core.research.EvtAutoThreshold
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationEngineTest {
    private fun record(vararg findings: Pair<String, Int>): RunRecord =
        RunRecord(
            timestamp = 1_000_000L,
            mrId = "mr-1",
            totalFindings = findings.sumOf { it.second },
            errorCount = 0,
            warningCount = 0,
            infoCount = 0,
            findingsByRule = mapOf(*findings),
        )

    private fun records(
        count: Int,
        vararg findings: Pair<String, Int>
    ): List<RunRecord> = List(count) { record(*findings) }

    @Test
    fun `too few samples returns empty`() {
        val recs = records(19, "title-format" to 1)
        val result = CalibrationEngine.analyze(recs, setOf("title-format"))
        assertTrue(result.isEmpty(), "Expected empty for < 20 samples")
    }

    @Test
    fun `exactly min samples threshold is accepted`() {
        val recs = records(20, "title-format" to 0)
        val result = CalibrationEngine.analyze(recs, setOf("title-format"))
        // 0 fire rate → REVIEW
        assertEquals(1, result.size)
        assertEquals(CalibrationAction.REVIEW, result.first().recommendation)
    }

    @Test
    fun `high fire rate recommends LOOSEN`() {
        // 18 out of 20 = 90% > 80%
        val recs = records(18, "title-format" to 1) + records(2, "title-format" to 0)
        val result = CalibrationEngine.analyze(recs, setOf("title-format"))
        assertEquals(1, result.size)
        val rec = result.first()
        assertEquals("title-format", rec.ruleId)
        assertEquals(CalibrationAction.LOOSEN, rec.recommendation)
        assertTrue(rec.currentFireRate > 0.8)
    }

    @Test
    fun `low fire rate recommends TIGHTEN`() {
        // 1 out of 20 = 5% - strictly less than 5%? No: 1/20 = 0.05 which equals LOW threshold.
        // Use 0 firing + 1 firing out of 25 = 1/25 = 4% < 5%
        val recs = records(1, "branch-naming" to 2) + records(24, "branch-naming" to 0)
        val result = CalibrationEngine.analyze(recs, setOf("branch-naming"))
        assertEquals(1, result.size)
        val rec = result.first()
        assertEquals(CalibrationAction.TIGHTEN, rec.recommendation)
        assertTrue(rec.currentFireRate > 0.0 && rec.currentFireRate < 0.05)
    }

    @Test
    fun `zero fire rate recommends REVIEW`() {
        val recs = records(20, "commit-messages" to 0)
        val result = CalibrationEngine.analyze(recs, setOf("commit-messages"))
        assertEquals(1, result.size)
        val rec = result.first()
        assertEquals(CalibrationAction.REVIEW, rec.recommendation)
        assertEquals(0.0, rec.currentFireRate)
        assertEquals(0.0, rec.averageFindingsPerRun)
    }

    @Test
    fun `moderate fire rate returns no recommendation`() {
        // 10 out of 20 = 50%: between 5% and 80%
        val recs = records(10, "size-limits" to 1) + records(10, "size-limits" to 0)
        val result = CalibrationEngine.analyze(recs, setOf("size-limits"))
        assertTrue(result.isEmpty(), "Moderate fire rate should produce no recommendation")
    }

    @Test
    fun `multiple rules analyzed independently`() {
        // rule-a: always fires (100% > 80%) → LOOSEN
        // rule-b: never fires (0%) → REVIEW
        // rule-c: moderate (50%) → no recommendation
        val recs = (1..20).map {
            RunRecord(
                timestamp = it.toLong(),
                mrId = "mr-$it",
                totalFindings = 2,
                errorCount = 0,
                warningCount = 0,
                infoCount = 0,
                findingsByRule = mapOf(
                    "rule-a" to 1,
                    "rule-b" to 0,
                    "rule-c" to if (it % 2 == 0) 1 else 0,
                ),
            )
        }
        val result = CalibrationEngine.analyze(recs, setOf("rule-a", "rule-b", "rule-c"))
        val byRule = result.associateBy { it.ruleId }
        assertEquals(CalibrationAction.LOOSEN, byRule["rule-a"]?.recommendation)
        assertEquals(CalibrationAction.REVIEW, byRule["rule-b"]?.recommendation)
        assertTrue("rule-c" !in byRule, "Moderate rule-c should not appear in recommendations")
    }

    @Test
    fun `recommendation reason is non-blank`() {
        val recs = records(20, "draft" to 0)
        val result = CalibrationEngine.analyze(recs, setOf("draft"))
        assertTrue(result.first().reason.isNotBlank())
    }

    private val risingRecords = (1..20).map { record("r" to it) }
    private val risingValues = (1..20).map { it.toDouble() }

    @Test
    fun `without evt config the legacy 95 percent confidence is used`() {
        val rec = CalibrationEngine.analyze(risingRecords, setOf("r")).single()
        assertEquals(EvtAutoThreshold.computeThreshold(risingValues, 0.95, rec.averageFindingsPerRun).threshold, rec.suggestedThreshold)
    }

    @Test
    fun `configured evt confidence and min samples drive the engine`() {
        val config = EvtAutoThresholdConfig(confidence = KarandashPercent.ofPercent(50))
        val rec = CalibrationEngine.analyze(risingRecords, setOf("r"), config).single()
        val expected = EvtAutoThreshold.computeThreshold(risingValues, 0.5, rec.averageFindingsPerRun, config).threshold
        assertEquals(expected, rec.suggestedThreshold)
        assertTrue(expected != CalibrationEngine.analyze(risingRecords, setOf("r")).single().suggestedThreshold)

        assertTrue(CalibrationEngine.analyze(risingRecords, setOf("r"), EvtAutoThresholdConfig(minSamples = 50)).isEmpty())
    }

    @Test
    fun `report applies the per-rule target and the history window`() {
        val recs = records(10, "a" to 1, "b" to 0) + records(10, "a" to 0, "b" to 0)
        val strict = CalibrationConfig(rules = listOf(CalibrationRuleTarget("a", targetFalsePositiveRate = 0.3)))
        val lines = CalibrationEngine.report(recs, strict, nowEpochSeconds = 1_000_000L)
        assertEquals("Rule calibration report (20 runs)", lines.first())
        assertTrue(lines.drop(1).single().startsWith("  a: fire rate 50% (target 30%) -> LOOSEN"))

        val all = CalibrationEngine.report(recs, CalibrationConfig(), nowEpochSeconds = 1_000_000L)
        assertTrue(all.any { it.startsWith("  b:") && "REVIEW" in it } && all.none { it.startsWith("  a:") })

        val windowed = CalibrationEngine.report(recs, strict.copy(historyWindow = 1), nowEpochSeconds = 1_000_000L + 2 * 86_400L)
        assertTrue(windowed.isEmpty())
    }

    @Test
    fun `report honors the configured evt min samples`() {
        val recs = records(20, "a" to 1)
        assertTrue(CalibrationEngine.report(recs, CalibrationConfig(), 1_000_000L).isNotEmpty())
        assertTrue(CalibrationEngine.report(recs, CalibrationConfig(), 1_000_000L, EvtAutoThresholdConfig(minSamples = 21)).isEmpty())
    }

    @Test
    fun `evt auto threshold is null unless configured`() {
        assertEquals(null, AiPluginConfig.fromPluginConfig(JsonObject(emptyMap())).research.evtAutoThreshold)
        val set = buildJsonObject {
            put("research", buildJsonObject { put("evtAutoThreshold", buildJsonObject { put("minSamples", 5) }) })
        }
        assertEquals(
            5,
            AiPluginConfig
                .fromPluginConfig(set)
                .research.evtAutoThreshold
                ?.minSamples
        )
    }

    @Test
    fun `report percentages are rounded not truncated by float error`() {
        // 0.58 * 100 and 0.57 * 100 land just below the integer in IEEE 754, so truncation printed 57% and 56%
        val recs = records(58, "a" to 1) + records(42, "a" to 0)
        val config = CalibrationConfig(rules = listOf(CalibrationRuleTarget("a", targetFalsePositiveRate = 0.57)))

        val line = CalibrationEngine.report(recs, config, nowEpochSeconds = 1_000_000L)[1]

        assertTrue(line.startsWith("  a: fire rate 58% (target 57%) -> LOOSEN: Rule fires on 58% of MRs"), line)
    }
}
