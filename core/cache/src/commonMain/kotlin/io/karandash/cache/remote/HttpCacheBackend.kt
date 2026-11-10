package io.karandash.cache.remote

import io.karandash.cache.cas.CasDigest
import io.karandash.logger.Logger

/**
 * HTTP remote cache backend using simple GET/PUT/HEAD/DELETE on `{baseUrl}/cas/{shard}/{remainder}`.
 *
 * Compatible with any HTTP server that supports these methods (nginx, Gradle cache node, custom).
 * Failures are logged, not thrown; [push] also reports whether the remote stored the blob.
 */
class HttpCacheBackend(
    private val baseUrl: String,
    private val fetcher: RemoteCacheFetcher,
    private val logger: Logger,
) : RemoteCacheBackend {
    override suspend fun exists(digest: CasDigest): Boolean {
        val url = blobUrl(digest)
        val response = fetcher.request("HEAD", url, null)
        return response.statusCode == HTTP_OK
    }

    override suspend fun pull(digest: CasDigest): ByteArray? {
        val url = blobUrl(digest)
        val response = fetcher.request("GET", url, null)
        return when (response.statusCode) {
            HTTP_OK -> {
                response.body
            }

            HTTP_NOT_FOUND -> {
                null
            }

            else -> {
                logger.debug("Remote cache pull failed for ${digest.hex.take(LOG_DIGEST_LEN)}: HTTP ${response.statusCode}")
                null
            }
        }
    }

    override suspend fun push(
        digest: CasDigest,
        content: ByteArray
    ): Boolean {
        val url = blobUrl(digest)
        val response = fetcher.request("PUT", url, content)
        val stored = response.statusCode in HTTP_SUCCESS_RANGE
        if (!stored) {
            logger.warn("Remote cache push failed for ${digest.hex.take(LOG_DIGEST_LEN)}: HTTP ${response.statusCode}")
        }
        return stored
    }

    override suspend fun delete(digest: CasDigest) {
        val url = blobUrl(digest)
        val response = fetcher.request("DELETE", url, null)
        if (response.statusCode !in HTTP_SUCCESS_RANGE && response.statusCode != HTTP_NOT_FOUND) {
            logger.debug("Remote cache delete failed for ${digest.hex.take(LOG_DIGEST_LEN)}: HTTP ${response.statusCode}")
        }
    }

    override suspend fun shutdown() {
        // HTTP backend is stateless -- nothing to close
    }

    private fun blobUrl(digest: CasDigest): String {
        val base = baseUrl.trimEnd('/')
        return "$base/cas/${digest.shard}/${digest.remainder}"
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        val HTTP_SUCCESS_RANGE = 200..299
        const val LOG_DIGEST_LEN = 12
    }
}
