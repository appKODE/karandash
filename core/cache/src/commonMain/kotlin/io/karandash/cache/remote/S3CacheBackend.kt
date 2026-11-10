package io.karandash.cache.remote

import io.karandash.crypto.AwsSigV4Request
import io.karandash.crypto.AwsSigV4Signer
import io.karandash.crypto.AwsStaticCredentials
import io.karandash.logger.Logger
import kotlinx.serialization.Serializable

/**
 * S3-compatible remote cache backend: an [HttpCacheBackend] on [baseUrl] (bucket URL plus the URI-encoded key
 * prefix, so the path sent is the one signed). With [credentials], every request is signed with AWS SigV4 for
 * [region]; without them requests are anonymous (a public or presigned-policy bucket).
 */
internal fun s3CacheBackend(
    baseUrl: String,
    fetcher: RemoteCacheFetcher,
    logger: Logger,
    credentials: AwsStaticCredentials?,
    region: String,
): RemoteCacheBackend = HttpCacheBackend(baseUrl, credentials?.let { SigV4Fetcher(fetcher, it, region) } ?: fetcher, logger)

/** Adds the SigV4 `Authorization` and `X-Amz-*` headers to each request before [delegate] sends it. */
private class SigV4Fetcher(
    private val delegate: RemoteCacheFetcher,
    private val credentials: AwsStaticCredentials,
    private val region: String,
) : RemoteCacheFetcher {
    override suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): RemoteCacheResponse {
        val target = SignedTarget.of(url)
        val signed = AwsSigV4Signer.sign(
            AwsSigV4Request(method, target.host, target.path, region, SERVICE, headers, target.query),
            body ?: ByteArray(0),
            credentials,
        )
        val signedHeaders = buildMap {
            putAll(headers)
            put("Authorization", signed.authorization)
            put("X-Amz-Date", signed.amzDate)
            put("X-Amz-Content-Sha256", signed.payloadSha256)
            signed.sessionToken?.let { put("X-Amz-Security-Token", it) }
        }
        return delegate.request(method, url, body, signedHeaders)
    }

    private companion object {
        const val SERVICE = "s3"
    }
}

/** The `Host` header an HTTP client sends for a URL (default port dropped), its path and its query. */
@Serializable
private data class SignedTarget(
    val host: String,
    val path: String,
    val query: String,
) {
    companion object {
        fun of(url: String): SignedTarget {
            val scheme = url.substringBefore("://").lowercase()
            val rest = url.substringAfter("://").substringBefore('#')
            val authority = rest.substringBefore('/').substringBefore('?')
            val pathAndQuery = rest.removePrefix(authority)
            val defaultPort = if (scheme == "https") ":443" else ":80"
            return SignedTarget(
                host = authority.substringAfterLast('@').lowercase().removeSuffix(defaultPort),
                path = "/" + pathAndQuery.substringBefore('?').removePrefix("/"),
                query = pathAndQuery.substringAfter('?', ""),
            )
        }
    }
}
