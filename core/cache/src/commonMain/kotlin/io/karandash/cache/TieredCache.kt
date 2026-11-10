package io.karandash.cache

import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.cas.CasCache
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.utils.BoundedCache
import io.karandash.utils.CacheStats
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Two-tier cache with in-memory [BoundedCache] and optional CAS-based file persistence.
 *
 * Lookup order:
 * 1. Check memory tier (fast path)
 * 2. If miss and file tier enabled → check CAS cache → promote to memory on hit
 *
 * Write path:
 * 1. Write to memory tier
 * 2. If file tier enabled → write to CAS cache (process-safe atomic write)
 *
 * Memory tier is independently thread-safe via [BoundedCache].
 * File-tier operations use [Mutex] with [tryLock] for non-suspend safety.
 *
 * @param name Human-readable cache name for logging and stats.
 * @param maxEntries Maximum entries in the memory tier.
 * @param ttl Optional time-to-live for both memory and file tier entries (defaults to 7 days for file tier).
 * @param tier Storage tier configuration.
 * @param serializer Serializer for file-tier persistence (required when tier is [CacheTier.MEMORY_AND_FILE]).
 * @param casCache CAS cache for persistent entries (required when tier is [CacheTier.MEMORY_AND_FILE]).
 * @param logger Logger for file-tier hit/miss decisions (debug; never logs keys or values).
 * @param readFiles False skips file-tier reads (`--no-cache`); entries are still written for the next run.
 */
class TieredCache<K, V>(
    val name: String,
    maxEntries: Int,
    ttl: Duration? = null,
    private val tier: CacheTier = CacheTier.MEMORY_ONLY,
    private val serializer: CacheSerializer<K, V>? = null,
    private val casCache: CasCache? = null,
    private val logger: Logger = NoOpLogger,
    private val readFiles: Boolean = true,
) {
    private val memory = BoundedCache<K, V>(maxEntries = maxEntries, ttl = ttl)
    private val fileTtlMs: Long = ttl?.inWholeMilliseconds ?: DEFAULT_TTL_MS
    private val fileMutex = Mutex()

    init {
        if (tier == CacheTier.MEMORY_AND_FILE) {
            requireNotNull(serializer) { "CacheSerializer required for MEMORY_AND_FILE tier" }
            requireNotNull(casCache) { "CasCache required for MEMORY_AND_FILE tier" }
        }
    }

    /**
     * Retrieves the value for [key], checking memory first, then file tier if enabled.
     *
     * File-tier access uses [withLock] to guarantee reads are never spuriously missed.
     * Waits for the lock instead of using `tryLock`, which returned null on contention
     * and caused spurious cache misses.
     *
     * @return The cached value, or null if not found in any tier.
     */
    suspend fun get(key: K): V? {
        memory.get(key)?.let { return it }

        if (!readFiles || tier != CacheTier.MEMORY_AND_FILE || serializer == null || casCache == null) {
            return null
        }

        return fileMutex.withLock {
            // Double-check memory after acquiring lock
            memory.get(key)?.let { return@withLock it }

            val keyStr = serializer.serializeKey(key)
            val bytes = casCache.fetch(keyStr)
            if (bytes == null) {
                logger.debug { "Cache [$name]: miss (not in memory or file tier, or expired)" }
                return@withLock null
            }
            // boundary: deserializing cached bytes - external data, type not guaranteed
            try {
                val value = serializer.deserializeValue(bytes.decodeToString())
                memory.put(key, value)
                logger.debug { "Cache [$name]: hit (file tier, promoted to memory)" }
                value
            } catch (ignored: Exception) {
                // boundary: deserializing cached bytes; external data, type not guaranteed
                ignored.rethrowCancellation()
                logger.warn("Cache [$name]: miss (file-tier entry unreadable)")
                null
            }
        }
    }

    /**
     * Returns the cached value for [key] from the memory tier only, without suspending.
     *
     * Use this from non-suspend contexts where file-tier access is not required.
     * File-tier misses will be filled on the next [get] call.
     *
     * @return The cached value from memory, or null if not present in memory tier.
     */
    fun getMemory(key: K): V? = memory.get(key)

    /**
     * Stores a value in memory and optionally persists to disk. A file-tier write that fails (full disk,
     * unwritable cache dir, unserializable value) is logged at `warn`; the memory entry stays and the caller
     * (e.g. a rule caching its result) keeps running.
     */
    fun put(
        key: K,
        value: V,
    ) {
        memory.put(key, value)

        if (tier == CacheTier.MEMORY_AND_FILE && serializer != null && casCache != null) {
            try {
                val keyStr = serializer.serializeKey(key)
                val bytes = serializer.serializeValue(value).encodeToByteArray()
                casCache.put(keyStr, bytes, fileTtlMs)
            } catch (e: IOException) {
                logger.warn("Cache [$name]: file-tier write failed, kept in memory only: ${e.message}")
            } catch (e: SerializationException) {
                logger.warn("Cache [$name]: value not serializable, kept in memory only: ${e.message}")
            }
        }
    }

    /**
     * Returns the cached value for [key], or computes, stores, and returns it.
     *
     * The [compute] function is called outside locks to avoid holding them
     * during potentially expensive computation.
     */
    fun getOrCompute(
        key: K,
        compute: () -> V,
    ): V {
        // Use memory-tier lookup only: non-suspend path for callers that cannot be suspend.
        // File-tier misses are filled on the next suspend get() call.
        memory.get(key)?.let { return it }
        val value = compute()
        put(key, value)
        return value
    }

    /** Removes a specific entry from all tiers. */
    fun invalidate(key: K) {
        memory.invalidate(key)
        if (tier == CacheTier.MEMORY_AND_FILE && serializer != null && casCache != null) {
            val keyStr = serializer.serializeKey(key)
            casCache.invalidate(keyStr)
        }
    }

    /** Removes all entries from all tiers and resets statistics. */
    fun clear() {
        memory.clear()
        casCache?.keys()?.forEach { casCache.invalidate(it) }
    }

    /** Current number of entries in the memory tier. */
    fun size(): Int = memory.size()

    /** Returns cache performance statistics for the memory tier. */
    fun stats(): CacheStats = memory.stats()

    /** Returns a copy of all non-expired entries from the memory tier for export/persistence. */
    fun snapshot(): Map<K, V> = memory.snapshot()

    /**
     * Populates the cache from a previously exported snapshot.
     *
     * Existing entries are NOT cleared - use [clear] first if a full replacement is needed.
     */
    fun importAll(entries: Map<K, V>) {
        memory.importAll(entries)
    }

    private companion object {
        /** Default TTL for file-tier entries: 7 days in milliseconds. */
        val DEFAULT_TTL_MS = 7.days.inWholeMilliseconds
    }
}
