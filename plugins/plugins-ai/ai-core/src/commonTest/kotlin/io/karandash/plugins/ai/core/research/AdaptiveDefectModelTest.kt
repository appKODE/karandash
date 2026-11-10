package io.karandash.plugins.ai.core.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveDefectModelTest {
    @Test
    fun `first update does not detect drift`() {
        val model = AdaptiveDefectModel()
        val result = model.update(0.5)
        assertFalse(result.driftDetected, "First update should never signal drift")
        assertEquals(0.5, result.newMean)
        assertEquals(0.0, result.deviation)
    }

    @Test
    fun `stable values do not trigger drift`() {
        val model = AdaptiveDefectModel()
        repeat(20) { model.update(0.3) }
        val result = model.update(0.3)
        assertFalse(result.driftDetected, "Stable identical values should not trigger drift")
    }

    @Test
    fun `sudden spike triggers drift after enough samples`() {
        val model = AdaptiveDefectModel()
        repeat(15) { model.update(0.2) }
        val spikeResult = model.update(0.99)
        assertTrue(spikeResult.driftDetected, "Large spike after stable period should trigger drift")
        assertTrue(spikeResult.deviation > 3.0, "Deviation should exceed threshold, got ${spikeResult.deviation}")
    }

    @Test
    fun `running mean updates with decay toward new observations`() {
        val model = AdaptiveDefectModel(decayFactor = 0.5)
        model.update(0.0)
        model.update(1.0)
        val mean = model.currentMean()
        assertTrue(mean > 0.0, "Mean should shift toward 1.0 after update, got $mean")
        assertTrue(mean < 1.0, "Mean should not reach 1.0 immediately, got $mean")
    }

    @Test
    fun `sample count increments correctly`() {
        val model = AdaptiveDefectModel()
        assertEquals(0, model.sampleCount())
        model.update(0.5)
        assertEquals(1, model.sampleCount())
        model.update(0.6)
        assertEquals(2, model.sampleCount())
        repeat(8) { model.update(0.4) }
        assertEquals(10, model.sampleCount())
    }

    @Test
    fun `drift not triggered before minimum sample threshold`() {
        val model = AdaptiveDefectModel()
        repeat(8) { model.update(0.1) }
        val earlySpike = model.update(0.99)
        assertFalse(earlySpike.driftDetected, "Drift should not trigger before minimum sample count (${model.sampleCount()} samples)")
    }

    @Test
    fun `new mean reflects decay factor influence`() {
        val fastModel = AdaptiveDefectModel(decayFactor = 0.1)
        fastModel.update(0.0)
        fastModel.update(1.0)
        val slowModel = AdaptiveDefectModel(decayFactor = 0.99)
        slowModel.update(0.0)
        slowModel.update(1.0)
        assertTrue(
            fastModel.currentMean() > slowModel.currentMean(),
            "Lower decay factor should react faster: fast=${fastModel.currentMean()} slow=${slowModel.currentMean()}",
        )
    }
}
