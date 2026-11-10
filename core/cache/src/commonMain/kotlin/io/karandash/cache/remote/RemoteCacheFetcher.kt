package io.karandash.cache.remote

import kotlinx.serialization.Serializable

/**
 * Abstraction for HTTP-like requests used by remote cache backends.
 *
 * Decouples cache backends from Ktor or any specific HTTP library.
 * Inject a concrete implementation at the CLI layer using [ProviderHttpClientFactory].
 */
fun interface RemoteCacheFetcher {
    /**
     * Execute an HTTP-like request sending [headers] (a signed S3 request's `Authorization` and `X-Amz-*`) - every
     * implementation must send them. [body] is null for GET/HEAD/DELETE.
     */
    suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): RemoteCacheResponse

    /** [request] without extra headers. */
    suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
    ): RemoteCacheResponse = request(method, url, body, emptyMap())
}

/**
 * Response from a [RemoteCacheFetcher] request.
 *
 * @property statusCode HTTP status code (e.g. 200, 404).
 * @property body Response body bytes, or null for HEAD/empty responses.
 */
@Serializable
data class RemoteCacheResponse(
    val statusCode: Int,
    val body: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteCacheResponse) return false
        return statusCode == other.statusCode && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}
