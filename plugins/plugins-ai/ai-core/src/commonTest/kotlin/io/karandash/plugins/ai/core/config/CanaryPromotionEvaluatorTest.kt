package io.karandash.plugins.ai.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanaryPromotionEvaluatorTest {
    @Test
    fun `ENFORCE stage stays ENFORCE regardless of metrics`() {
        val config = CanaryConfig(
            stage = CanaryStage.ENFORCE,
            autoPromote = AutoPromoteConfig(afterDays = 0, maxFalsePositiveRate = 1.0, minSampleSize = 0),
        )
        val metrics = CanaryMetrics(sampleSize = 1000, falsePositiveRate = 0.0, daysSinceStart = 999)
        assertEquals(CanaryStage.ENFORCE, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `SHADOW with insufficient sample size stays SHADOW`() {
        val config = CanaryConfig(
            stage = CanaryStage.SHADOW,
            autoPromote = AutoPromoteConfig(minSampleSize = 50, maxFalsePositiveRate = 0.10, afterDays = 14),
        )
        val metrics = CanaryMetrics(sampleSize = 10, falsePositiveRate = 0.05, daysSinceStart = 20)
        assertEquals(CanaryStage.SHADOW, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `SHADOW with high false-positive rate stays SHADOW`() {
        val config = CanaryConfig(
            stage = CanaryStage.SHADOW,
            autoPromote = AutoPromoteConfig(minSampleSize = 50, maxFalsePositiveRate = 0.10, afterDays = 14),
        )
        val metrics = CanaryMetrics(sampleSize = 100, falsePositiveRate = 0.50, daysSinceStart = 20)
        assertEquals(CanaryStage.SHADOW, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `SHADOW with insufficient days stays SHADOW`() {
        val config = CanaryConfig(
            stage = CanaryStage.SHADOW,
            autoPromote = AutoPromoteConfig(minSampleSize = 50, maxFalsePositiveRate = 0.10, afterDays = 14),
        )
        val metrics = CanaryMetrics(sampleSize = 100, falsePositiveRate = 0.05, daysSinceStart = 5)
        assertEquals(CanaryStage.SHADOW, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `SHADOW auto-promotes to CANARY when all criteria met`() {
        val config = CanaryConfig(
            stage = CanaryStage.SHADOW,
            autoPromote = AutoPromoteConfig(
                minSampleSize = 50,
                maxFalsePositiveRate = 0.10,
                afterDays = 14,
                promoteTo = CanaryStage.CANARY,
            ),
        )
        val metrics = CanaryMetrics(sampleSize = 100, falsePositiveRate = 0.05, daysSinceStart = 20)
        assertEquals(CanaryStage.CANARY, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `SHADOW without autoPromote config stays SHADOW`() {
        val config = CanaryConfig(stage = CanaryStage.SHADOW, autoPromote = null)
        val metrics = CanaryMetrics(sampleSize = 1000, falsePositiveRate = 0.0, daysSinceStart = 999)
        assertEquals(CanaryStage.SHADOW, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `CANARY without autoPromote config stays CANARY`() {
        val config = CanaryConfig(stage = CanaryStage.CANARY, autoPromote = null)
        val metrics = CanaryMetrics(sampleSize = 1000, falsePositiveRate = 0.0, daysSinceStart = 999)
        assertEquals(CanaryStage.CANARY, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `CANARY auto-promotes to ENFORCE when all criteria met`() {
        val config = CanaryConfig(
            stage = CanaryStage.CANARY,
            autoPromote = AutoPromoteConfig(
                minSampleSize = 50,
                maxFalsePositiveRate = 0.10,
                afterDays = 14,
                promoteTo = CanaryStage.ENFORCE,
            ),
        )
        val metrics = CanaryMetrics(sampleSize = 100, falsePositiveRate = 0.05, daysSinceStart = 20)
        assertEquals(CanaryStage.ENFORCE, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `shouldReport returns false for SHADOW`() {
        assertFalse(CanaryPromotionEvaluator.shouldReport(CanaryStage.SHADOW))
    }

    @Test
    fun `shouldReport returns true for CANARY`() {
        assertTrue(CanaryPromotionEvaluator.shouldReport(CanaryStage.CANARY))
    }

    @Test
    fun `shouldReport returns true for ENFORCE`() {
        assertTrue(CanaryPromotionEvaluator.shouldReport(CanaryStage.ENFORCE))
    }

    @Test
    fun `shouldBlock returns false for SHADOW`() {
        assertFalse(CanaryPromotionEvaluator.shouldBlock(CanaryStage.SHADOW))
    }

    @Test
    fun `shouldBlock returns false for CANARY`() {
        assertFalse(CanaryPromotionEvaluator.shouldBlock(CanaryStage.CANARY))
    }

    @Test
    fun `shouldBlock returns true for ENFORCE`() {
        assertTrue(CanaryPromotionEvaluator.shouldBlock(CanaryStage.ENFORCE))
    }

    @Test
    fun `false-positive rate at exact threshold boundary promotes`() {
        val config = CanaryConfig(
            stage = CanaryStage.SHADOW,
            autoPromote = AutoPromoteConfig(minSampleSize = 50, maxFalsePositiveRate = 0.10, afterDays = 14),
        )
        val metrics = CanaryMetrics(sampleSize = 50, falsePositiveRate = 0.10, daysSinceStart = 14)
        assertEquals(CanaryStage.CANARY, CanaryPromotionEvaluator.evaluateStage(config, metrics))
    }

    @Test
    fun `percentOfMrs samples a stable share of MRs in the canary stage only`() {
        val config = CanaryConfig(stage = CanaryStage.CANARY, percentOfMrs = 30)
        val picked = (1..1000).count { CanaryPromotionEvaluator.inRollout(config, CanaryStage.CANARY, "r", "mr-$it") }
        assertTrue(picked in 230..370, "picked $picked of 1000")
        assertEquals(
            CanaryPromotionEvaluator.inRollout(config, CanaryStage.CANARY, "r", "mr-7"),
            CanaryPromotionEvaluator.inRollout(config, CanaryStage.CANARY, "r", "mr-7"),
        )
        assertTrue((1..50).all { CanaryPromotionEvaluator.inRollout(config, CanaryStage.ENFORCE, "r", "mr-$it") })
        assertFalse(CanaryPromotionEvaluator.inRollout(config.copy(percentOfMrs = 0), CanaryStage.CANARY, "r", "mr-1"))
    }
}
