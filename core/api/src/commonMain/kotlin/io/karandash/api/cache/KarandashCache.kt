package io.karandash.api.cache

/**
 * Universal string-based cache interface for cross-language plugin access.
 *
 * All modules get a [KarandashCache] via [CacheProvider.getStringCache].
 * Thread-safe, bounded, configurable by default.
 */
public interface KarandashCache {
    /** Returns the cached value for [key], or null if absent or expired. */
    public suspend fun get(key: String): String?

    /**
     * Stores [value] under [key].
     *
     * @param ttlMs Optional per-entry TTL in milliseconds. 0 means use the cache default.
     */
    public suspend fun put(
        key: String,
        value: String,
        ttlMs: Long = 0
    )

    /**
     * Returns the cached value for [key], or computes and stores it via [compute].
     */
    public suspend fun getOrPut(
        key: String,
        compute: suspend () -> String,
    ): String

    /**
     * Returns cached value if [dependencies] haven't changed since last store.
     *
     * Dependencies are hashed and stored alongside the value. If any dependency
     * value changed since the last call, [compute] runs and the result is stored
     * with the new dependency hash.
     *
     * @param key Cache key.
     * @param dependencies Map of dependency names to their current values.
     * @param compute Suspend function that produces the value when cache is stale.
     * @return The cached or freshly computed value.
     */
    public suspend fun getOrPut(
        key: String,
        dependencies: Map<String, String>,
        compute: suspend () -> String,
    ): String

    /** Removes the entry for [key]. */
    public suspend fun invalidate(key: String)

    /** Removes all entries. */
    public suspend fun clear()

    /** Returns the current number of entries. */
    public fun size(): Int
}
