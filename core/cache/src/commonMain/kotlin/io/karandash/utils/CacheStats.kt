package io.karandash.utils

import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of cache performance statistics.
 *
 * @param hits Number of successful lookups (key found and not expired).
 * @param misses Number of failed lookups (key absent, expired, or evicted).
 * @param evictions Number of entries evicted due to capacity limits (0 for a cache that never evicts).
 * @param size Current number of entries in the cache (0 for a cache that does not hold entries itself).
 * @param deduplicatedWrites Number of writes that were skipped because the value was unchanged.
 * @param pushes Blobs a remote layer stored on the remote.
 * @param pushFailures Pushes the remote refused, failed or timed out.
 */
@Serializable
data class CacheStats(
    val hits: Long,
    val misses: Long,
    val evictions: Long = 0,
    val size: Int = 0,
    val deduplicatedWrites: Long = 0,
    val pushes: Long = 0,
    val pushFailures: Long = 0,
) {
    /** Hit rate as a fraction in [0.0, 1.0]. Returns 0.0 when no lookups have occurred. */
    val hitRate: Double
        get() {
            val total = hits + misses
            return if (total == 0L) 0.0 else hits.toDouble() / total
        }
}
