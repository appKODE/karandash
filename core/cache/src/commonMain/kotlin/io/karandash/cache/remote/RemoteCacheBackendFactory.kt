package io.karandash.cache.remote

import io.karandash.crypto.AwsCredentialChain
import io.karandash.crypto.AwsSigV4Signer
import io.karandash.crypto.AwsStaticCredentials
import io.karandash.logger.Logger

/**
 * Factory for creating [RemoteCacheBackend] instances from configuration.
 *
 * Supported backends: `http`, `s3`, `gcs`. Each is an [HttpCacheBackend] on the URL plus `cache.remote.prefix`
 * (URI-encoded per segment); `s3` adds SigV4 signing, `gcs` is plain HTTP against the bucket URL.
 */
object RemoteCacheBackendFactory {
    /** Supported backend type identifiers. */
    private const val HTTP = "http"
    const val S3 = "s3"
    private const val GCS = "gcs"

    /**
     * Create a [RemoteCacheBackend] for the given configuration.
     *
     * [credentialChain] supplies S3 signing credentials when the config has no explicit key pair, and the region when
     * [regionExplicit] is false; it is used only for an AWS host ([AwsCredentialChain.isAwsHost]) - S3-compatible
     * hosts (MinIO, R2) need explicit keys. Pass null (the default) where the ambient AWS credentials must not be
     * sent: a URL other than `cache.remote.url` (`cache push --backend-url`).
     *
     * @throws IllegalStateException for unsupported backends.
     */
    fun create(
        config: RemoteCacheConfig,
        fetcher: RemoteCacheFetcher,
        logger: Logger,
        credentialChain: AwsCredentialChain? = null,
        regionExplicit: Boolean = true,
    ): RemoteCacheBackend =
        when (config.backend) {
            HTTP, GCS -> {
                HttpCacheBackend(withPrefix(config.url, config.prefix), fetcher, logger)
            }

            S3 -> {
                val chain = credentialChain?.takeIf { AwsCredentialChain.isAwsHost(config.url) }
                if (credentialChain != null && chain == null) {
                    logger.debug("cache.remote: not an https AWS host, ambient AWS credentials not used")
                }
                s3CacheBackend(
                    withPrefix(config.url, config.prefix),
                    fetcher,
                    logger,
                    s3Credentials(config, logger) ?: chain?.credentials(config.profile),
                    if (regionExplicit || chain == null) config.region else chain.region(config.profile),
                )
            }

            else -> {
                error("Unknown remote cache backend: '${config.backend}'. Supported: http, s3, gcs")
            }
        }

    /** Signing credentials when both `access-key-id` and `secret-access-key` are set; one alone is a warning. */
    private fun s3Credentials(
        config: RemoteCacheConfig,
        logger: Logger,
    ): AwsStaticCredentials? {
        val keyId = config.accessKeyId?.takeIf { it.isNotBlank() }
        val secret = config.secretAccessKey?.takeIf { it.isNotBlank() }
        if ((keyId == null) != (secret == null)) {
            logger.warn("cache.remote: set both access-key-id and secret-access-key to sign S3 requests; ignoring the one set")
        }
        if (keyId == null || secret == null) return null
        return AwsStaticCredentials(keyId, secret, config.sessionToken?.takeIf { it.isNotBlank() })
    }

    /** Blobs live under `{url}/{prefix}/cas/…`; each prefix segment is URI-encoded, so a space or `#` stays in the path. */
    internal fun withPrefix(
        url: String,
        prefix: String,
    ): String {
        val key = prefix.trim('/')
        if (key.isEmpty()) return url
        return "${url.trimEnd('/')}/${key.split('/').joinToString("/") { AwsSigV4Signer.uriEncode(it) }}"
    }
}
