package io.karandash.cache

import io.karandash.api.cache.KarandashCache

/**
 * Adapts a [TieredCache]<String, String> to the [KarandashCache] interface.
 *
 * Delegates all operations to the underlying tiered cache. The `suspend`
 * modifier on [KarandashCache] methods is forward-compatible - current
 * [TieredCache] operations are synchronous but the interface allows
 * future async implementations (e.g. remote caches).
 *
 * Dependency-aware [getOrPut] stores a hash alongside the value using
 * a `key:depHash` companion entry. When the dependency hash changes,
 * the cached value is treated as stale and recomputed.
 */
internal class KarandashCacheAdapter(
    private val delegate: TieredCache<String, String>,
) : KarandashCache {
    override suspend fun get(key: String): String? = delegate.get(key)

    override suspend fun put(
        key: String,
        value: String,
        ttlMs: Long,
    ) {
        delegate.put(key, value)
    }

    override suspend fun getOrPut(
        key: String,
        compute: suspend () -> String,
    ): String {
        delegate.get(key)?.let { return it }
        val value = compute()
        delegate.put(key, value)
        return value
    }

    override suspend fun getOrPut(
        key: String,
        dependencies: Map<String, String>,
        compute: suspend () -> String,
    ): String {
        val depHash = DependencyHash.compute(dependencies)
        val metaKey = "$key$DEP_HASH_SUFFIX"
        val storedHash = delegate.get(metaKey)
        if (storedHash == depHash) {
            val cached = delegate.get(key)
            if (cached != null) return cached
        }
        val value = compute()
        delegate.put(key, value)
        delegate.put(metaKey, depHash)
        return value
    }

    override suspend fun invalidate(key: String) {
        delegate.invalidate(key)
        delegate.invalidate("$key$DEP_HASH_SUFFIX")
    }

    override suspend fun clear() {
        delegate.clear()
    }

    override fun size(): Int = delegate.size()

    private companion object {
        /** Suffix appended to cache keys for dependency hash companion entries. */
        const val DEP_HASH_SUFFIX = ":dh"
    }
}
