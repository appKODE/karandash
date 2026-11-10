package io.karandash.http

import io.karandash.cache.cas.ConditionalFetch
import io.karandash.cache.cas.ConditionalFetchCache
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.cache.cas.ConditionalStore
import io.karandash.crypto.sha256Hex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ETag-aware HTTP response cache for provider API calls.
 *
 * Delegates to an [ETagTieredCache] for storage, eviction, and TTL management.
 * Cache key is derived from `{provider}:{method}:{url}` via SHA-256.
 *
 * Entries expire after [defaultTtl]. [getOrFetch] runs the whole conditional GET through core/cache's
 * [ConditionalFetchCache] over this cache: a live entry is served, an expired one with an ETag is revalidated
 * (`If-None-Match`, 304 refreshes its TTL), anything else is fetched and stored. [get], [put] and [refreshTtl]
 * stay for callers that drive the steps themselves.
 *
 * @param cache The underlying ETag-aware tiered cache.
 * @param defaultTtl Default time-to-live for cache entries.
 */
class ProviderResponseCache(
    private val cache: ETagTieredCache,
    private val defaultTtl: Duration = DEFAULT_TTL,
) {
    /**
     * Creates a standalone [ProviderResponseCache] with a memory-only backing cache.
     *
     * @param maxEntries Maximum number of cached entries (LRU eviction).
     * @param defaultTtl Default time-to-live for cache entries.
     */
    constructor(
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        defaultTtl: Duration = DEFAULT_TTL,
    ) : this(
        cache = ETagTieredCache.memoryOnly(
            name = CACHE_NAME,
            maxEntries = maxEntries,
            defaultTtl = defaultTtl,
        ),
        defaultTtl = defaultTtl,
    )

    private val conditional = ConditionalFetchCache(EtagStore())

    /** Striped single-flight locks: concurrent [getOrFetch] calls for one key share a single fetch; bounded, no per-key growth. */
    private val flights = List(FLIGHT_STRIPES) { Mutex() }

    /**
     * The response for [url]: cached while live, revalidated with its ETag once expired, else fetched with
     * [fetch] and stored for [defaultTtl]. [fetch] receives the ETag to send as `If-None-Match` (null for a full
     * fetch) and must return 200 or, for a conditional request, 304; it throws for any other status.
     * Concurrent calls for the same URL fetch once: the later ones find the stored response.
     */
    suspend fun getOrFetch(
        provider: String,
        method: String,
        url: String,
        fetch: ConditionalFetch,
    ): ConditionalResponse {
        val key = cacheKey(provider, method, url)
        return flights[key.hashCode().mod(FLIGHT_STRIPES)].withLock {
            conditional.getOrFetchResponse(key, url, defaultTtl.inWholeMilliseconds, fetch)
        }
    }

    /**
     * Look up a cached response.
     *
     * @param provider Provider name (e.g. "gitlab", "github").
     * @param method HTTP method (e.g. "GET").
     * @param url Full request URL.
     * @return The cached entry if present and not expired, or an expired entry with an ETag
     *         for conditional request support. Returns null if no entry exists.
     */
    suspend fun get(
        provider: String,
        method: String,
        url: String,
    ): CacheLookup {
        val key = cacheKey(provider, method, url)
        return when (val hit = cache.get(key)) {
            is ETagCacheHit.Fresh -> CacheLookup(
                hit = hit.response.toCacheEntry(),
                staleEtag = null,
            )

            is ETagCacheHit.StaleWithEtag -> CacheLookup(
                hit = null,
                staleEtag = hit.etag,
            )

            null -> CacheLookup(hit = null, staleEtag = null)
        }
    }

    /**
     * Store a response in the cache.
     *
     * @param provider Provider name.
     * @param method HTTP method.
     * @param url Full request URL.
     * @param responseBody The serialized response body.
     * @param etag ETag header value from the response, if present.
     * @param ttl Override TTL for this entry. Defaults to [defaultTtl].
     * @param nextLink `Link: rel="next"` URL of a paginated response.
     */
    fun put(
        provider: String,
        method: String,
        url: String,
        responseBody: String,
        etag: String? = null,
        ttl: Duration = defaultTtl,
        nextLink: String? = null,
    ) {
        val key = cacheKey(provider, method, url)
        cache.put(key = key, body = responseBody, etag = etag, ttl = ttl, nextLink = nextLink)
    }

    /**
     * Refresh the TTL of an existing cache entry (e.g. after receiving HTTP 304).
     *
     * @param provider Provider name.
     * @param method HTTP method.
     * @param url Full request URL.
     * @param newEtag Updated ETag, if the server returned a new one.
     * @return The cached entry if it was refreshed, or null if not found.
     */
    suspend fun refreshTtl(
        provider: String,
        method: String,
        url: String,
        newEtag: String? = null,
    ): CacheEntry? {
        val key = cacheKey(provider, method, url)
        return cache.refreshTtl(key, newEtag)?.toCacheEntry()
    }

    /** Number of entries currently in the cache. */
    fun size(): Int = cache.size()

    /** Remove all entries. */
    fun clear() {
        cache.clear()
    }

    /** [ConditionalStore] view of [cache]; a 304 keeps the entry's own TTL. */
    private inner class EtagStore : ConditionalStore {
        override suspend fun live(key: String): ConditionalResponse? = (cache.get(key) as? ETagCacheHit.Fresh)?.response?.toConditional()

        override suspend fun etag(key: String): String? = (cache.get(key) as? ETagCacheHit.StaleWithEtag)?.etag

        override suspend fun refresh(
            key: String,
            ttlMs: Long,
        ): ConditionalResponse? = cache.refreshTtl(key)?.toConditional()

        override fun put(
            key: String,
            response: ConditionalResponse,
            ttlMs: Long,
        ) = cache.put(key, response.content, response.etag, ttlMs.milliseconds, response.nextLink)

        private fun CachedHttpResponse.toConditional() = ConditionalResponse(HTTP_OK, body, etag, nextLink)
    }

    private fun cacheKey(
        provider: String,
        method: String,
        url: String,
    ): String = sha256Hex("$provider:$method:$url")

    companion object {
        private const val HTTP_OK = 200
        private const val FLIGHT_STRIPES = 16
        const val CACHE_NAME = "http-responses"
        const val DEFAULT_MAX_ENTRIES = 128
        val DEFAULT_TTL: Duration = 60.seconds
    }
}

/**
 * A single cached HTTP response entry.
 */
@Serializable
data class CacheEntry(
    /** The serialized response body. */
    val responseBody: String,
    /** ETag header value for conditional requests. */
    val etag: String?,
    /** When this entry was stored or last refreshed (epoch seconds). */
    val timestampEpochSeconds: Long,
    /** How long this entry is valid (seconds). */
    val ttlSeconds: Long,
    /** `Link: rel="next"` URL of a paginated response. */
    val nextLink: String? = null,
)

/**
 * Result of a cache lookup.
 *
 * - [hit] is non-null when the entry is fresh (within TTL).
 * - [staleEtag] is non-null when the entry is expired but has an ETag
 *   for conditional request support (If-None-Match).
 * - Both null means a cache miss.
 */
@Serializable
data class CacheLookup(
    val hit: CacheEntry?,
    val staleEtag: String?,
)

/** Convert a [CachedHttpResponse] to a [CacheEntry]. */
private fun CachedHttpResponse.toCacheEntry(): CacheEntry =
    CacheEntry(
        responseBody = body,
        etag = etag,
        timestampEpochSeconds = storedAtEpochSeconds,
        ttlSeconds = ttlSeconds,
        nextLink = nextLink,
    )
