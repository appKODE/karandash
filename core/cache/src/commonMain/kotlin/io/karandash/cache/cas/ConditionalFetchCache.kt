package io.karandash.cache.cas

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import kotlinx.serialization.Serializable

/** One HTTP response of a conditional GET: status, body, the `ETag` header and the `Link: rel="next"` URL. */
@Serializable
data class ConditionalResponse(
    val statusCode: Int,
    val content: String,
    val etag: String? = null,
    val nextLink: String? = null,
)

/** A GET for one URL that sends `If-None-Match: <etag>` when [etag] is non-null. */
fun interface ConditionalFetch {
    suspend fun fetch(etag: String?): ConditionalResponse
}

/** Storage behind a [ConditionalFetchCache]: live entries, the ETag of an expired one, TTL refresh and writes. */
interface ConditionalStore {
    /** The entry for [key] while its TTL holds, else null. */
    suspend fun live(key: String): ConditionalResponse?

    /** The ETag of the (expired) entry for [key], or null. */
    suspend fun etag(key: String): String?

    /** Restart the TTL of [key] after a 304; the entry, or null when it is gone. */
    suspend fun refresh(
        key: String,
        ttlMs: Long,
    ): ConditionalResponse?

    fun put(
        key: String,
        response: ConditionalResponse,
        ttlMs: Long,
    )
}

/** [ConditionalStore] over a [CasCache] (the remote `extends:` cache); does not keep `nextLink`. */
class CasConditionalStore(
    private val casCache: CasCache,
) : ConditionalStore {
    override suspend fun live(key: String): ConditionalResponse? =
        casCache.get(key)?.let { ConditionalResponse(HTTP_OK, it.decodeToString()) }

    override suspend fun etag(key: String): String? = casCache.getIndex(key)?.etag

    override suspend fun refresh(
        key: String,
        ttlMs: Long,
    ): ConditionalResponse? {
        casCache.refreshTtl(key, ttlMs)
        return live(key)
    }

    override fun put(
        key: String,
        response: ConditionalResponse,
        ttlMs: Long,
    ) {
        casCache.put(key, response.content.encodeToByteArray(), ttlMs, etag = response.etag)
    }

    /** Remove every entry whose key starts with [prefix]. */
    fun clear(prefix: String) {
        casCache.keys().filter { it.startsWith(prefix) }.forEach(casCache::invalidate)
    }
}

/**
 * TTL + ETag cache for remote text content over a [ConditionalStore].
 *
 * A live entry is returned as is. An expired entry with an ETag is revalidated: 304 refreshes its TTL,
 * 200 replaces it. A miss (or an unexpected revalidation status) does a full fetch, which must return 200.
 * [kotlinx.io.IOException] from the store propagates so the caller can fall back to a direct fetch.
 */
class ConditionalFetchCache(
    private val store: ConditionalStore,
) {
    constructor(casCache: CasCache) : this(CasConditionalStore(casCache))

    /** Content for [key], fetched with [fetch] when missing or expired; stored for [ttlMs]. */
    suspend fun getOrFetch(
        key: String,
        url: String,
        ttlMs: Long,
        fetch: ConditionalFetch,
    ): String = getOrFetchResponse(key, url, ttlMs, fetch).content

    /** [getOrFetch] returning the whole stored response (status 200), next link included. */
    suspend fun getOrFetchResponse(
        key: String,
        url: String,
        ttlMs: Long,
        fetch: ConditionalFetch,
    ): ConditionalResponse {
        store.live(key)?.let { return it }
        val etag = store.etag(key)
        if (etag != null) {
            val result = fetch.fetch(etag)
            if (result.statusCode == HTTP_NOT_MODIFIED) {
                // null: entry evicted between the ETag read and the refresh; fall through to a full fetch
                store.refresh(key, ttlMs)?.let { return it }
            } else if (result.statusCode == HTTP_OK) {
                return result.also { store.put(key, it, ttlMs) }
            }
        }
        val result = fetch.fetch(null)
        if (result.statusCode != HTTP_OK) {
            throw ConfigException(
                "${KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode} Failed to fetch config " +
                    "from $url: HTTP ${result.statusCode}",
            )
        }
        return result.also { store.put(key, it, ttlMs) }
    }
}

private const val HTTP_OK = 200
private const val HTTP_NOT_MODIFIED = 304
