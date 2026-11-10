package io.karandash.api.cache

/**
 * Abstraction over the cache subsystem, exposed to plugins via [io.karandash.api.PluginContext].
 *
 * Concrete implementation lives in `core/cache` ([io.karandash.cache.CacheManager]).
 * This interface keeps `core/api` free of `core/cache` dependencies.
 */
public interface CacheProvider {
    /** Whether caching is globally enabled. */
    public val enabled: Boolean

    /**
     * Returns a [KarandashCache] (string key/value) scoped to [name].
     *
     * If a cache with [name] already exists, returns the existing instance.
     * The [maxEntries] parameter is used only when creating a new cache.
     *
     * @param name Unique cache name (e.g. module ID or subsystem name).
     * @param maxEntries Maximum entries in the memory tier. Defaults to 256.
     * @return A bounded, thread-safe [KarandashCache].
     */
    public fun getStringCache(
        name: String,
        maxEntries: Int = DEFAULT_MAX_ENTRIES
    ): KarandashCache

    public companion object {
        /** Default maximum entries for string caches. */
        public const val DEFAULT_MAX_ENTRIES: Int = 256

        /**
         * A no-op [CacheProvider] that returns caches which never store anything.
         *
         * Used as the default in [io.karandash.api.PluginContext] so that modules
         * can call cache methods without null checks, even when caching is disabled.
         */
        public val NOOP: CacheProvider = NoOpCacheProvider
    }
}

/**
 * No-op implementation that discards all values.
 *
 * Returned by [CacheProvider.NOOP] for contexts without a real cache subsystem
 * (tests, early bootstrap, disabled caching).
 */
internal object NoOpCacheProvider : CacheProvider {
    override val enabled: Boolean = false

    override fun getStringCache(
        name: String,
        maxEntries: Int
    ): KarandashCache = NoOpKarandashCache
}

/**
 * No-op cache that never stores anything.
 */
internal object NoOpKarandashCache : KarandashCache {
    override suspend fun get(key: String): String? = null

    override suspend fun put(
        key: String,
        value: String,
        ttlMs: Long
    ) { /* no-op */ }

    override suspend fun getOrPut(
        key: String,
        compute: suspend () -> String
    ): String = compute()

    override suspend fun getOrPut(
        key: String,
        dependencies: Map<String, String>,
        compute: suspend () -> String,
    ): String = compute()

    override suspend fun invalidate(key: String) { /* no-op */ }

    override suspend fun clear() { /* no-op */ }

    override fun size(): Int = 0
}
