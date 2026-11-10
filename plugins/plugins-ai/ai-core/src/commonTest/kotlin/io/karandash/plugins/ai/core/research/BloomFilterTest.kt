package io.karandash.plugins.ai.core.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BloomFilterTest {
    @Test
    fun `added item is found`() {
        val filter = BloomFilter()
        filter.add("src/Main.kt")
        assertTrue(filter.mightContain("src/Main.kt"))
    }

    @Test
    fun `non-added item is not found when filter is empty`() {
        val filter = BloomFilter()
        // An empty filter has all bits false, so mightContain must return false.
        assertTrue(!filter.mightContain("src/Missing.kt"))
    }

    @Test
    fun `itemCount tracks additions`() {
        val filter = BloomFilter()
        assertEquals(0, filter.itemCount())
        filter.add("a")
        assertEquals(1, filter.itemCount())
        filter.add("b")
        assertEquals(2, filter.itemCount())
    }

    @Test
    fun `itemCount increments on duplicate adds`() {
        val filter = BloomFilter()
        filter.add("dup")
        filter.add("dup")
        assertEquals(2, filter.itemCount())
    }

    @Test
    fun `fromPatterns builds pre-filled filter`() {
        val patterns = listOf("*.kt", "*.java", "*.ts")
        val filter = BloomFilter.fromPatterns(patterns)
        assertEquals(3, filter.itemCount())
        for (p in patterns) {
            assertTrue(filter.mightContain(p), "Expected mightContain($p) to be true")
        }
    }

    @Test
    fun `falsePositiveProbability returns value in valid range`() {
        val filter = BloomFilter()
        val emptyProb = filter.falsePositiveProbability()
        assertTrue(emptyProb in 0.0..1.0, "Empty filter probability out of range: $emptyProb")

        repeat(50) { filter.add("item-$it") }
        val filledProb = filter.falsePositiveProbability()
        assertTrue(filledProb in 0.0..1.0, "Filled filter probability out of range: $filledProb")
    }

    @Test
    fun `falsePositiveProbability increases as filter fills`() {
        val filter = BloomFilter(size = 64)
        val probBefore = filter.falsePositiveProbability()
        repeat(30) { filter.add("item-$it") }
        val probAfter = filter.falsePositiveProbability()
        assertTrue(probAfter >= probBefore, "FP probability should not decrease as filter fills")
    }

    @Test
    fun `larger size reduces false positive probability`() {
        val small = BloomFilter.fromPatterns(List(20) { "item-$it" }, size = 64)
        val large = BloomFilter.fromPatterns(List(20) { "item-$it" }, size = 4096)
        assertTrue(
            large.falsePositiveProbability() <= small.falsePositiveProbability(),
            "Larger filter should have lower FP rate",
        )
    }
}
