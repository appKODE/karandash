package io.karandash.http

import io.karandash.cache.CacheSerializer
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Cached HTTP response stored in the tiered cache.
 *
 * The [storedAtEpochSeconds] and [ttlSeconds] fields support ETag-based
 * conditional request logic: when an entry is expired but has an [etag],
 * callers can issue a conditional request with `If-None-Match` and call
 * [ETagTieredCache.refreshTtl] on HTTP 304.
 */
@Serializable
data class CachedHttpResponse(
    /** The serialized response body. */
    val body: String,
    /** ETag header value for conditional requests. */
    val etag: String? = null,
    /** When this entry was stored or last refreshed (epoch seconds). */
    val storedAtEpochSeconds: Long = 0,
    /** How long this entry is valid (seconds). */
    val ttlSeconds: Long = 0,
    /** `Link: rel="next"` URL of a paginated response, so cached pages keep paginating. */
    val nextLink: String? = null,
)

/**
 * Result of an ETag-aware cache lookup.
 *
 * - [Fresh]: the entry is within its TTL - use the cached response directly.
 * - [StaleWithEtag]: the entry is expired but has an ETag - issue a conditional
 *   request with `If-None-Match` and call [ETagTieredCache.refreshTtl] on HTTP 304.
 * - `null` return means a cache miss (no entry or expired without ETag).
 */
sealed class ETagCacheHit {
    /** Entry is within TTL - use directly without an HTTP call. */
    @Serializable
    data class Fresh(
        val response: CachedHttpResponse
    ) : ETagCacheHit()

    /** Entry expired but has an ETag for conditional request support. */
    @Serializable
    data class StaleWithEtag(
        val response: CachedHttpResponse,
        val etag: String,
    ) : ETagCacheHit()
}

/**
 * ETag-aware HTTP response cache backed by a [TieredCache].
 *
 * Wraps a `TieredCache<String, CachedHttpResponse>` and adds TTL expiry
 * checking with ETag-based conditional request support. Thread safety
 * is inherited from [TieredCache]'s internal [io.karandash.utils.BoundedCache].
 *
 * @param cache The underlying tiered cache for storage and eviction.
 * @param defaultTtl Default time-to-live for new entries.
 */
class ETagTieredCache(
    private val cache: TieredCache<String, CachedHttpResponse>,
    private val defaultTtl: Duration,
) {
    /**
     * Look up a cached response with TTL and ETag awareness.
     *
     * @param key Cache key (typically a hash of provider + method + URL).
     * @return [ETagCacheHit.Fresh] if within TTL, [ETagCacheHit.StaleWithEtag]
     *         if expired with an ETag, or null on miss.
     */
    suspend fun get(key: String): ETagCacheHit? {
        val entry = cache.get(key) ?: return null
        val now = Clock.System.now().epochSeconds
        val expired = entry.ttlSeconds > 0 && (now - entry.storedAtEpochSeconds) > entry.ttlSeconds
        return if (!expired) {
            ETagCacheHit.Fresh(entry)
        } else if (entry.etag != null) {
            ETagCacheHit.StaleWithEtag(entry, entry.etag)
        } else {
            null
        }
    }

    /**
     * Store a response in the cache with the [defaultTtl].
     *
     * @param key Cache key.
     * @param body Response body string.
     * @param etag ETag header value from the response, if present.
     * @param ttl Override TTL for this entry. Defaults to [defaultTtl].
     */
    fun put(
        key: String,
        body: String,
        etag: String? = null,
        ttl: Duration = defaultTtl,
        nextLink: String? = null,
    ) {
        cache.put(
            key,
            CachedHttpResponse(
                body = body,
                etag = etag,
                storedAtEpochSeconds = Clock.System.now().epochSeconds,
                // a positive sub-second TTL rounds up: 0 would mean "never expires"
                ttlSeconds = if (ttl.isPositive()) ttl.inWholeSeconds.coerceAtLeast(1) else ttl.inWholeSeconds,
                nextLink = nextLink,
            ),
        )
    }

    /**
     * Refresh the TTL of an existing entry (e.g. after receiving HTTP 304).
     *
     * @param key Cache key.
     * @param newEtag Updated ETag, if the server returned a new one.
     * @return The refreshed cached response, or null if no entry exists for [key].
     */
    suspend fun refreshTtl(
        key: String,
        newEtag: String? = null,
    ): CachedHttpResponse? {
        val existing = cache.get(key) ?: return null
        val refreshed = existing.copy(
            storedAtEpochSeconds = Clock.System.now().epochSeconds,
            etag = newEtag ?: existing.etag,
        )
        cache.put(key, refreshed)
        return refreshed
    }

    /** Current number of entries in the cache. */
    fun size(): Int = cache.size()

    /** Remove all entries. */
    fun clear() {
        cache.clear()
    }

    companion object {
        /**
         * Creates an [ETagTieredCache] from a [TieredCache] spec.
         *
         * Convenience factory for the common case of creating a memory-only
         * HTTP response cache with a given capacity and TTL.
         */
        fun memoryOnly(
            name: String,
            maxEntries: Int,
            defaultTtl: Duration,
        ): ETagTieredCache {
            val tieredCache = TieredCache<String, CachedHttpResponse>(
                name = name,
                maxEntries = maxEntries,
                tier = CacheTier.MEMORY_ONLY,
            )
            return ETagTieredCache(cache = tieredCache, defaultTtl = defaultTtl)
        }
    }
}

/**
 * [CacheSerializer] for [CachedHttpResponse] using JSON serialization.
 *
 * Enables file-tier persistence when using [CacheTier.MEMORY_AND_FILE].
 */
object CachedHttpResponseSerializer : CacheSerializer<String, CachedHttpResponse> {
    override fun serializeKey(key: String): String = key

    override fun serializeValue(value: CachedHttpResponse): String = KarandashJson.Default.encodeToString(value)

    override fun deserializeValue(data: String): CachedHttpResponse = KarandashJson.Default.decodeFromString(data)
}

/**
 * Creates a [CacheSpec] suitable for HTTP response caching with file-tier persistence.
 *
 * @param maxEntries Maximum entries in the memory tier.
 * @param ttl Time-to-live for cache entries.
 */
fun httpResponseCacheSpec(
    maxEntries: Int,
    ttl: Duration? = null,
    tier: CacheTier = CacheTier.MEMORY_ONLY,
): CacheSpec<String, CachedHttpResponse> =
    CacheSpec(
        maxEntries = maxEntries,
        ttl = ttl,
        tier = tier,
        serializer = if (tier == CacheTier.MEMORY_AND_FILE) CachedHttpResponseSerializer else null,
    )
