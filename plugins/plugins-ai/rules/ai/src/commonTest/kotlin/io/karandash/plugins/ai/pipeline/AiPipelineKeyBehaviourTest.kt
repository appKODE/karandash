package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.percent.KarandashPercent
import io.karandash.plugins.ai.core.config.AiCloneDetectionConfig
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.config.AiPipelineThresholdsConfig
import io.karandash.plugins.ai.core.config.AiReportsConfig
import io.karandash.plugins.ai.core.performance.PgoProfile
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Effect of the `ai.pipeline` thresholds, clone detection and report minimums, and of `ai.pgoEnabled`. */
class AiPipelineKeyBehaviourTest {
    private val base = listOf(Finding(severity = Severity.WARNING, message = "m", rule = "r"))

    private fun change(
        path: String,
        diff: String? = null,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, diffContent = diff)

    private fun enricher(config: AiPipelineConfig) = AiMetricsEnricher(config, NoOpLogger)

    @Test
    fun `pipeline cloneDetection maxFindings caps the clone findings`() {
        val body = (1..10).joinToString("\n") { "+val value$it = compute($it) + offset" }
        val mr = createTestMergeRequest(changes = listOf("A.kt", "B.kt", "C.kt").map { change(it, body) })
        val options = AiMetricsOptions(cloneDetection = CloneDetectionOptions(threshold = 0.5))

        fun clones(config: AiPipelineConfig) =
            enricher(config).enrich(base, mr, emptySet(), options).count { it.rule == "metrics:clone-detection" }

        assertEquals(3, clones(AiPipelineConfig()))
        assertEquals(1, clones(AiPipelineConfig(cloneDetection = AiCloneDetectionConfig(maxFindings = 1))))
    }

    @Test
    fun `pipeline thresholds highEntropy decides when shotgun surgery is reported`() {
        val mr = createTestMergeRequest(changes = (1..8).map { change("dir$it/F$it.kt") })

        fun reported(config: AiPipelineConfig) =
            enricher(config).enrich(base, mr, setOf("change-entropy")).any { it.rule == "metrics:change-entropy" }

        val never = AiPipelineThresholdsConfig(highEntropy = KarandashPercent.ofPercent(100))
        assertTrue(reported(AiPipelineConfig()))
        assertFalse(reported(AiPipelineConfig(thresholds = never)))
    }

    @Test
    fun `pipeline thresholds highSurpriseTokens decides when unusual patterns are reported`() {
        val diff = "+" + "a b c ".repeat(30) + "a b d"
        val mr = createTestMergeRequest(changes = listOf(change("N.kt", diff)))

        fun reported(tokens: Int) =
            enricher(AiPipelineConfig(thresholds = AiPipelineThresholdsConfig(highSurpriseTokens = tokens)))
                .enrich(base, mr, setOf("naturalness"))
                .any { it.rule == "metrics:naturalness" }

        assertTrue(reported(0))
        assertFalse(reported(HUGE))
    }

    @Test
    fun `pipeline reports minRecordsRequired and minRecordsForCrossPlatform gate the trend reports`() {
        val history = (0 until HISTORY).map { i ->
            RunRecord(
                timestamp = i.toLong(),
                mrId = "mr-$i",
                totalFindings = 5,
                errorCount = 5,
                warningCount = 0,
                infoCount = 0,
                findingsByRule = mapOf("flaky" to i % 2, "drifty" to if (i < HISTORY / 2) 0 else 5),
                platform = "gitlab",
                author = "dev",
            )
        }
        val enabled = setOf("cross-platform", "flaky-rules", "policy-drift")
        val mr = createTestMergeRequest()

        fun keys(config: AiPipelineConfig) = AiReportGenerator(config, NoOpLogger, history).generate(base, mr, null, "mr-1", enabled).keys

        assertEquals(enabled, keys(AiPipelineConfig()))
        assertEquals(
            setOf("cross-platform"),
            keys(AiPipelineConfig(reports = AiReportsConfig(minRecordsRequired = HISTORY + 1))),
        )
        assertEquals(
            setOf("flaky-rules", "policy-drift"),
            keys(AiPipelineConfig(reports = AiReportsConfig(minRecordsForCrossPlatform = HISTORY + 1))),
        )
    }

    @Test
    fun `pgoEnabled adds the pgo profile report only when set`() {
        PgoProfile.reset()
        try {
            PgoProfile.enableProfiling()
            PgoProfile.recordPath("key-behaviour")
            val generator = AiReportGenerator(AiPipelineConfig(), NoOpLogger, emptyList())
            val enabled = setOf("pgo-profile")
            val mr = createTestMergeRequest()

            val off = generator.generate(base, mr, buildJsonObject { put("pgoEnabled", false) }, "mr-1", enabled)
            val on = generator.generate(base, mr, buildJsonObject { put("pgoEnabled", true) }, "mr-1", enabled)

            assertFalse("pgo-profile" in off)
            assertTrue("pgo-profile" in on)
        } finally {
            PgoProfile.reset()
        }
    }

    private companion object {
        const val HUGE = 1_000_000
        const val HISTORY = 8
    }
}
