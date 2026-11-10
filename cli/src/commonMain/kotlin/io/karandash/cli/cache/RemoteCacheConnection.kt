package io.karandash.cli.cache

import io.karandash.cache.remote.RemoteCacheBackend
import io.karandash.cache.remote.RemoteCacheBackendFactory
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.crypto.AwsCredentialChain
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/** The HTTP client and backend of one remote cache; whoever holds it closes [client] (see [use]). */
internal class RemoteCacheConnection(
    val client: HttpClient,
    val backend: RemoteCacheBackend,
) {
    /** Runs [block] on [backend], then shuts the backend down and closes [client]. */
    suspend fun <T> use(block: suspend (RemoteCacheBackend) -> T): T =
        try {
            block(backend)
        } finally {
            try {
                backend.shutdown()
            } finally {
                client.close()
            }
        }
}

/**
 * The one wiring of a remote cache backend, for validation runs and the `cache` subcommands: an HTTP client
 * ([allowLocalhost] permits a loopback or private URL; signed S3 requests never follow a redirect, the session
 * token header would go along), a [KtorRemoteCacheFetcher] with `auth` and the `max-blob-size` pull guard, and
 * the backend of [remote]. [chain] signs S3 requests without explicit keys; [regionExplicit] tells whether a
 * config layer set `cache.remote.region`. [engine] is for tests.
 */
internal fun connectRemoteCache(
    remote: RemoteCacheConfig,
    logger: Logger,
    allowLocalhost: Boolean,
    chain: AwsCredentialChain?,
    regionExplicit: Boolean,
    engine: HttpClientEngine? = null,
): RemoteCacheConnection {
    val client = ProviderHttpClientFactory.create(
        engine = engine,
        logger = logger,
        allowLocalhost = allowLocalhost,
        followRedirects = remote.backend != RemoteCacheBackendFactory.S3,
    )
    val fetcher = KtorRemoteCacheFetcher(client, remote.auth, remote.maxBlobSize.bytes)
    return RemoteCacheConnection(client, RemoteCacheBackendFactory.create(remote, fetcher, logger, chain, regionExplicit))
}
