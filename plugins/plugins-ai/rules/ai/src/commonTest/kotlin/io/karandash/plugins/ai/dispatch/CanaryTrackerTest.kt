@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.module.ExtensionStateStore
import io.karandash.duration.KarandashDuration
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.config.AutoPromoteConfig
import io.karandash.plugins.ai.core.config.CanaryConfig
import io.karandash.plugins.ai.core.config.CanaryMetrics
import io.karandash.plugins.ai.core.config.CanaryStage
import io.karandash.plugins.ai.core.config.TrackConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanaryTrackerTest {
    private val day = 86_400_000L

    private class MemoryStore : ExtensionStateStore {
        val entries = mutableMapOf<String, ByteArray>()
        override val ttl: KarandashDuration = KarandashDuration.ofSeconds(60)

        override fun get(key: String): ByteArray? = entries[key]

        override fun put(
            key: String,
            value: ByteArray,
        ) {
            entries[key] = value
        }

        override fun invalidate(key: String) {
            entries.remove(key)
        }

        override fun keys(): List<String> = entries.keys.toList()
    }

    private fun finding(message: String) = Finding(rule = "r", severity = Severity.WARNING, message = message)

    @Test
    fun `metrics accumulate across runs through persistence and drive promotion`() {
        val persistence = AiStatePersistence(MemoryStore())
        val clock = longArrayOf(0L)
        val config = CanaryConfig(
            stage = CanaryStage.SHADOW,
            autoPromote = AutoPromoteConfig(afterDays = 1, maxFalsePositiveRate = 0.5, minSampleSize = 2),
        )
        val firstRun = CanaryTracker(persistence) { clock[0] }
        assertEquals(CanaryMetrics(sampleSize = 1), firstRun.recordSample("r", config))
        val feedback = FeedbackStore().also { it.dismiss("r", null, "noise".hashCode()) }
        firstRun.recordFindings("r", listOf(finding("noise"), finding("real")), feedback)
        firstRun.persist()

        clock[0] = 2 * day
        val evaluator = AiSkipEvaluator(
            logger = NoOpLogger,
            ruleGate = RuleGate(),
            canaryTracker = CanaryTracker(persistence) { clock[0] },
        )
        val result = evaluator.evaluateCanary("r", mapOf("r" to config))
        assertEquals(SkipResult.Continue, result, "promoted after 2 samples, 2 days, 50% FP")
    }

    @Test
    fun `the same MR twice is one sample`() {
        val tracker = CanaryTracker(persistence = null) { 0L }
        val evaluator = AiSkipEvaluator(logger = NoOpLogger, ruleGate = RuleGate(), canaryTracker = tracker)
        val canary = mapOf("r" to CanaryConfig(stage = CanaryStage.CANARY, percentOfMrs = 100))
        evaluator.evaluateCanary("r", canary, mrId = "42")
        evaluator.evaluateCanary("r", canary, mrId = "42")
        assertEquals(1, tracker.metricsWith("r", canary.getValue("r"), mrId = "42").sampleSize)
        assertEquals(2, tracker.metricsWith("r", canary.getValue("r"), mrId = "43").sampleSize)
    }

    @Test
    fun `an MR outside the rollout is not a sample`() {
        val tracker = CanaryTracker(persistence = null) { 0L }
        val evaluator = AiSkipEvaluator(logger = NoOpLogger, ruleGate = RuleGate(), canaryTracker = tracker)
        val config = CanaryConfig(stage = CanaryStage.CANARY, percentOfMrs = 0)
        assertTrue(evaluator.evaluateCanary("r", mapOf("r" to config), mrId = "42") is SkipResult.Skip)
        assertEquals(1, tracker.metricsWith("r", config, mrId = "43").sampleSize, "only the prospective MR counts")
    }

    @Test
    fun `a different configured stage starts the observations over`() {
        val tracker = CanaryTracker(persistence = null) { 0L }
        tracker.recordSample("r", CanaryConfig(stage = CanaryStage.SHADOW))
        tracker.recordSample("r", CanaryConfig(stage = CanaryStage.SHADOW))
        assertEquals(CanaryMetrics(sampleSize = 1), tracker.recordSample("r", CanaryConfig(stage = CanaryStage.CANARY)))
    }

    @Test
    fun `findings of rules that were not sampled are ignored`() {
        val store = MemoryStore()
        val tracker = CanaryTracker(AiStatePersistence(store))
        tracker.recordFindings("r", listOf(finding("x")), FeedbackStore())
        tracker.persist()
        assertEquals(emptyMap(), store.entries)
    }

    @Test
    fun `environment track disableRules skips the AI rule`() {
        val evaluator = AiSkipEvaluator(logger = NoOpLogger, ruleGate = RuleGate())
        val tracks = listOf(TrackConfig(name = "prod", targetBranchPattern = "^main$", disableRules = listOf("ai-security")))
        assertTrue(evaluator.evaluateEnvironmentTrack("ai-security-scan", "main", tracks) is SkipResult.Skip)
        assertEquals(SkipResult.Continue, evaluator.evaluateEnvironmentTrack("ai-review", "main", tracks))
    }
}
