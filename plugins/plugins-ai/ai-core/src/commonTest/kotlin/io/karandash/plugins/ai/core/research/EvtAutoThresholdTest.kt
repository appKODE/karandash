package io.karandash.plugins.ai.core.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvtAutoThresholdTest {
    private val sampleData = List(50) { (it + 1).toDouble() } // 1.0 .. 50.0

    @Test
    fun `insufficient data returns fallback`() {
        val result = EvtAutoThreshold.computeThreshold(
            historicalValues = listOf(1.0, 2.0, 3.0),
            fallback = 500.0,
        )
        assertEquals(500.0, result.threshold)
        assertFalse(result.isDataDriven)
        assertEquals(3, result.sampleSize)
    }

    @Test
    fun `exactly at minimum sample boundary returns fallback`() {
        val borderline = List(19) { it.toDouble() }
        val result = EvtAutoThreshold.computeThreshold(
            historicalValues = borderline,
            fallback = 100.0,
        )
        assertFalse(result.isDataDriven)
    }

    @Test
    fun `data-driven threshold computed from sufficient samples`() {
        val result = EvtAutoThreshold.computeThreshold(
            historicalValues = sampleData,
            fallback = 0.0,
        )
        assertTrue(result.isDataDriven)
        assertEquals(50, result.sampleSize)
        assertTrue(result.threshold > 0.0, "Threshold should be positive")
    }

    @Test
    fun `isDataDriven flag set correctly`() {
        val small = EvtAutoThreshold.computeThreshold(listOf(1.0), fallback = 1.0)
        assertFalse(small.isDataDriven)

        val large = EvtAutoThreshold.computeThreshold(sampleData, fallback = 1.0)
        assertTrue(large.isDataDriven)
    }

    @Test
    fun `higher confidence produces more permissive threshold`() {
        val low = EvtAutoThreshold.computeThreshold(sampleData, confidence = 0.5, fallback = 0.0)
        val high = EvtAutoThreshold.computeThreshold(sampleData, confidence = 0.99, fallback = 0.0)
        assertTrue(
            high.threshold >= low.threshold,
            "Higher confidence should yield higher threshold: ${high.threshold} vs ${low.threshold}",
        )
    }

    @Test
    fun `confidence stored in result`() {
        val result = EvtAutoThreshold.computeThreshold(sampleData, confidence = 0.95, fallback = 0.0)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `threshold at least as large as percentile value`() {
        val result = EvtAutoThreshold.computeThreshold(sampleData, fallback = 0.0)
        assertTrue(
            result.threshold >= result.percentileValue,
            "Threshold should be >= percentile value",
        )
    }
}
