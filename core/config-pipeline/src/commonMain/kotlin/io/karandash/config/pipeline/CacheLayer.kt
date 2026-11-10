package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.cache.cas.CasCache
import io.karandash.cache.cas.CasConditionalStore
import io.karandash.cache.cas.ConditionalFetchCache
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.crypto.sha256Hex
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.manifest.CachePolicy
import io.karandash.manifest.ConfigSource
import io.karandash.security.UrlSanitizer
import kotlinx.io.IOException

/**
 * CAS-backed cache layer for remote configuration files used in `extends:` chains: decides whether a source
 * is cached and for how long; the TTL/ETag cache itself is core/cache's [ConditionalFetchCache].
 *
 * Implements HTTP caching with TTL-based expiration and ETag-based conditional
 * requests. Cache failures degrade gracefully to direct fetches with warnings.
 *
 * Each remote URL is stored under key `"extends-{sha256(url)}"` in the provided
 * [CasCache]. The [CasCache] index entry carries the ETag and Last-Modified
 * headers natively; no separate metadata sidecar is needed.
 *
 * @param fetcher Underlying HTTP fetcher for remote content.
 * @param casCache CAS cache for the `"downloads"` namespace.
 * @param defaultTtl Default TTL when no [CachePolicy] is provided (`cache.downloads.ttl`).
 * @param enabled `cache.downloads.enabled`; false fetches every remote config directly.
 * @param logger Logger for warnings and debug messages.
 */
class CacheLayer(
    private val fetcher: RemoteConfigFetcher,
    private val casCache: CasCache,
    private val defaultTtl: KarandashDuration = DEFAULT_CACHE_TTL,
    private val logger: Logger = NoOpLogger,
    private val enabled: Boolean = true,
) {
    private val store = CasConditionalStore(casCache)
    private val cache = ConditionalFetchCache(store)

    /**
     * This layer fetching through [fetcher], with the `cache.downloads` fields an `extends:` step inherits; a null
     * [ttl] or [enabled] (not set on that path) keeps this layer's own. Same CAS store.
     */
    fun with(
        fetcher: RemoteConfigFetcher,
        ttl: KarandashDuration?,
        enabled: Boolean?,
    ): CacheLayer =
        if (fetcher === this.fetcher && ttl == null && enabled == null) {
            this
        } else {
            CacheLayer(fetcher, casCache, ttl ?: defaultTtl, logger, this.enabled && (enabled ?: true))
        }

    private companion object {
        val DEFAULT_CACHE_TTL: KarandashDuration = KarandashDuration.ofSeconds(3600)
        const val KEY_PREFIX = "extends-"

        /** TTL used for CachePolicy(ttl = NEVER) - effectively cache-forever (~10 years). */
        const val FOREVER_TTL_MS: Long = 10L * 365 * 24 * 60 * 60 * 1000L
    }

    /**
     * Get cached content or fetch from the remote source.
     *
     * Logic:
     * 1. Local file paths bypass the cache entirely.
     * 2. `CachePolicy(ttl = 0)` bypasses the cache.
     * 3. If a non-expired CAS entry exists, return cached content.
     * 4. If expired and an ETag is present, send a conditional request.
     *    - HTTP 304 → refresh TTL, return cached content.
     *    - HTTP 200 → store new content + ETag, return new content.
     * 5. Cache miss → fetch, store, return.
     * 6. On [IOException] → log warning (E0019) and fall back to direct fetch.
     *
     * @param source The config source to fetch.
     * @param cachePolicy The cache policy from the extends entry (nullable).
     * @return The configuration content as a string.
     * @throws ConfigException if the fetch fails (cache failures degrade gracefully).
     */
    suspend fun getOrFetch(
        source: ConfigSource,
        cachePolicy: CachePolicy?,
    ): String {
        // Only cache HTTP(S) URLs
        if (!enabled || (!source.url.startsWith("http://") && !source.url.startsWith("https://"))) {
            logger.debug { "CacheLayer: bypass for ${UrlSanitizer.sanitize(source.url)} (enabled=$enabled; only HTTP(S) is cached)" }
            return fetcher.fetch(source)
        }

        // TTL = 0 means no caching
        if (cachePolicy?.ttl == KarandashDuration.ZERO) {
            logger.debug { "CacheLayer: bypass for ${UrlSanitizer.sanitize(source.url)} (cache ttl=0)" }
            return fetcher.fetch(source)
        }

        val effectiveTtl = cachePolicy?.ttl ?: defaultTtl
        logger.debug { "CacheLayer: cached fetch of ${UrlSanitizer.sanitize(source.url)} (ttl=$effectiveTtl)" }
        val ttlMs = if (effectiveTtl == KarandashDuration.NEVER) FOREVER_TTL_MS else effectiveTtl.millis
        val key = KEY_PREFIX + sha256Hex(source.url)

        return try {
            cache.getOrFetch(key, source.url, ttlMs) { etag ->
                val result = fetcher.fetchWithETag(source.url, etag, source.auth, source.emptyAuthVars)
                ConditionalResponse(result.statusCode, result.content, result.etag)
            }
        } catch (e: IOException) {
            logger.warn(
                "${KarandashError.CACHE_OPERATION_FAILED.bracketCode} Cache operation failed for " +
                    "${UrlSanitizer.sanitize(source.url)}: ${e.message}. Falling back to direct fetch."
            )
            fetcher.fetch(source)
        }
    }

    /**
     * Clear all cached entries managed by this cache layer.
     */
    fun clearAll() = store.clear(KEY_PREFIX)

    /**
     * Clear expired cache entries.
     *
     * Expired entries are skipped on read ([CasCache.get] returns null) and
     * cleaned from disk by [CacheGarbageCollector] during pipeline shutdown.
     * This method is retained for API compatibility but performs no work itself.
     */
    fun clearExpired() {
        // Expired index entries and orphaned blobs are cleaned by GC on shutdown.
    }
}
