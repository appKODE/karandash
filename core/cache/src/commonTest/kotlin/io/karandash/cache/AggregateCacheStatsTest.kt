package io.karandash.cache

import io.karandash.utils.CacheStats
import kotlin.test.Test
import kotlin.test.assertEquals

class AggregateCacheStatsTest {
    @Test
    fun aggregatesMultipleCacheStats() {
        val stats = AggregateCacheStats(
            perCache = mapOf(
                "a" to CacheStats(hits = 10, misses = 5, evictions = 2, size = 8),
                "b" to CacheStats(hits = 20, misses = 3, evictions = 1, size = 12),
            ),
        )
        assertEquals(30L, stats.totalHits)
        assertEquals(8L, stats.totalMisses)
        assertEquals(3L, stats.totalEvictions)
        assertEquals(20, stats.totalSize)
    }

    @Test
    fun hitRateIsCorrect() {
        val stats = AggregateCacheStats(
            perCache = mapOf(
                "a" to CacheStats(hits = 75, misses = 25, evictions = 0, size = 10),
            ),
        )
        assertEquals(0.75, stats.hitRate, 0.001)
    }

    @Test
    fun hitRateIsZeroWhenNoLookups() {
        val stats = AggregateCacheStats(perCache = emptyMap())
        assertEquals(0.0, stats.hitRate)
    }

    @Test
    fun emptyPerCacheReturnsZeros() {
        val stats = AggregateCacheStats(perCache = emptyMap())
        assertEquals(0L, stats.totalHits)
        assertEquals(0L, stats.totalMisses)
        assertEquals(0L, stats.totalEvictions)
        assertEquals(0, stats.totalSize)
    }
}
