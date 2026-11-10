package io.karandash.cache

import io.karandash.utils.CacheStats
import kotlinx.serialization.Serializable

/**
 * Combined statistics across all managed caches.
 *
 * @param perCache Per-cache stats keyed by cache name.
 */
@Serializable
data class AggregateCacheStats(
    val perCache: Map<String, CacheStats>,
) {
    /** Total hits across all caches. */
    val totalHits: Long get() = perCache.values.sumOf { it.hits }

    /** Total misses across all caches. */
    val totalMisses: Long get() = perCache.values.sumOf { it.misses }

    /** Total evictions across all caches. */
    val totalEvictions: Long get() = perCache.values.sumOf { it.evictions }

    /** Total entries across all caches. */
    val totalSize: Int get() = perCache.values.sumOf { it.size }

    /** Aggregate hit rate across all caches. */
    val hitRate: Double
        get() {
            val total = totalHits + totalMisses
            return if (total == 0L) 0.0 else totalHits.toDouble() / total
        }
}
