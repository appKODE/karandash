package io.karandash.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Thread-safe, bounded in-memory cache with SLRU (Segmented LRU) eviction.
 *
 * SLRU splits the cache into two segments:
 * - **Probationary** (~20% of capacity): new entries land here.
 * - **Protected** (~80% of capacity): entries promoted on second access.
 *
 * Eviction always removes from probationary first, giving frequently accessed
 * entries better survival odds than plain LRU. This is well-suited for mixed
 * recency/frequency workloads (e.g. some glob patterns are hot across all rules,
 * others are one-off).
 *
 * Operations take a short [synchronized] lock (atomicfu, KMP). Nothing is skipped under
 * contention: invalidate and clear always take effect, and snapshot is always complete.
 *
 * @param maxEntries Maximum total entries across both segments.
 * @param ttl Optional time-to-live. Entries older than this are treated as absent.
 * @param clock Clock supplier for TTL checks (injectable for testing).
 */
class BoundedCache<K, V>(
    private val maxEntries: Int,
    private val ttl: Duration? = null,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val lock = SynchronizedObject()

    // Probationary segment: new entries land here. ~20% of capacity.
    private val probationary = LinkedHashMap<K, Entry<V>>()

    // Protected segment: entries promoted on second access. ~80% of capacity.
    private val protected0 = LinkedHashMap<K, Entry<V>>()

    // Soft target for protected segment (~80%). Used to decide when to demote on promotion.
    private val protectedCapacity: Int get() = (maxEntries * PROTECTED_RATIO).toInt().coerceAtLeast(1)

    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    /**
     * Retrieves the value associated with [key], or `null` if absent or expired.
     *
     * A hit in the probationary segment promotes the entry to protected.
     */
    fun get(key: K): V? = synchronized(lock) { getInternal(key) }

    /**
     * Inserts or updates an entry. New entries go into the probationary segment.
     */
    fun put(
        key: K,
        value: V
    ) {
        synchronized(lock) { putInternal(key, value) }
    }

    /**
     * Returns the cached value for [key], or computes, stores, and returns it.
     *
     * The [compute] function is called outside the lock to avoid holding
     * the lock during potentially expensive computation. This means concurrent
     * calls for the same key may both compute, but only the last write wins.
     */
    fun getOrCompute(
        key: K,
        compute: () -> V
    ): V {
        synchronized(lock) { getInternal(key) }?.let { return it }

        val value = compute()
        synchronized(lock) { putInternal(key, value) }
        return value
    }

    /** Removes a specific entry. */
    fun invalidate(key: K) {
        synchronized(lock) {
            probationary.remove(key)
            protected0.remove(key)
        }
    }

    /** Removes all entries and resets statistics. */
    fun clear() {
        synchronized(lock) {
            probationary.clear()
            protected0.clear()
            hits = 0L
            misses = 0L
            evictions = 0L
        }
    }

    /** Current number of entries across both segments. */
    fun size(): Int = synchronized(lock) { probationary.size + protected0.size }

    /** Returns a snapshot of cache performance statistics. */
    fun stats(): CacheStats =
        synchronized(lock) {
            CacheStats(
                hits = hits,
                misses = misses,
                evictions = evictions,
                size = probationary.size + protected0.size,
            )
        }

    /** Returns a copy of all non-expired entries for export/persistence. */
    fun snapshot(): Map<K, V> =
        synchronized(lock) {
            val now = clock()
            val result = LinkedHashMap<K, V>()
            for ((k, entry) in protected0) {
                if (!isExpired(entry, now)) result[k] = entry.value
            }
            for ((k, entry) in probationary) {
                if (!isExpired(entry, now)) result[k] = entry.value
            }
            result
        }

    /**
     * Populates the cache from a previously exported snapshot.
     *
     * Existing entries are NOT cleared - use [clear] first if a full replacement is needed.
     * If the snapshot exceeds [maxEntries], only the last entries (by iteration order) are kept.
     */
    fun importAll(entries: Map<K, V>) {
        synchronized(lock) {
            for ((key, value) in entries) {
                putInternal(key, value)
            }
        }
    }

    // --- Internal (caller must hold lock) ---

    private fun getInternal(key: K): V? {
        val now = clock()

        // Check protected segment first (more likely to be hit)
        protected0[key]?.let { entry ->
            if (isExpired(entry, now)) {
                protected0.remove(key)
                misses++
                return null
            }
            // Re-insert to move to end (MRU position)
            protected0.remove(key)
            protected0[key] = entry
            hits++
            return entry.value
        }

        // Check probationary segment - promote to protected on hit
        probationary[key]?.let { entry ->
            if (isExpired(entry, now)) {
                probationary.remove(key)
                misses++
                return null
            }
            probationary.remove(key)
            promoteToProtected(key, entry)
            hits++
            return entry.value
        }

        misses++
        return null
    }

    private fun putInternal(
        key: K,
        value: V
    ) {
        val entry = Entry(value, clock())

        // If already in protected, update in place
        if (protected0.containsKey(key)) {
            protected0.remove(key)
            protected0[key] = entry
            return
        }

        // If already in probationary, update in place (don't promote - not a "get")
        if (probationary.containsKey(key)) {
            probationary.remove(key)
            probationary[key] = entry
            return
        }

        // New entry - enforce global capacity, then insert into probationary
        ensureCapacity()
        probationary[key] = entry
    }

    private fun promoteToProtected(
        key: K,
        entry: Entry<V>
    ) {
        // Entry was already removed from probationary by caller.
        // If protected is at its soft capacity, demote its LRU to probationary.
        if (protected0.size >= protectedCapacity) {
            val eldest = protected0.entries.first()
            val eldestKey = eldest.key
            val eldestValue = eldest.value
            protected0.remove(eldestKey)
            probationary[eldestKey] = eldestValue
        }
        protected0[key] = entry
    }

    /** Evicts from probationary first, then protected, until total < maxEntries. */
    private fun ensureCapacity() {
        while (probationary.size + protected0.size >= maxEntries) {
            val probationaryEldestKey = probationary.keys.firstOrNull()
            if (probationaryEldestKey != null) {
                probationary.remove(probationaryEldestKey)
            } else {
                val protectedEldestKey = protected0.keys.firstOrNull() ?: break
                protected0.remove(protectedEldestKey)
            }
            evictions++
        }
    }

    private fun isExpired(
        entry: Entry<V>,
        now: Instant
    ): Boolean {
        val t = ttl ?: return false
        return now - entry.createdAt > t
    }

    private class Entry<V>(
        val value: V,
        val createdAt: Instant,
    )

    companion object {
        /** Protected segment gets ~80% of capacity. */
        private const val PROTECTED_RATIO = 0.8
    }
}
