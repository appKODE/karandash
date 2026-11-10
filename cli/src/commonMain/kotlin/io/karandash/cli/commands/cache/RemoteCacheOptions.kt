package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cli.cache.RemoteCacheConnection
import io.karandash.cli.cache.connectRemoteCache
import io.karandash.cli.pipeline.ConfigLoadStage
import io.karandash.cli.pipeline.PipelineInfrastructure
import io.karandash.crypto.AwsCredentialChain
import io.karandash.sdk.Logger
import io.ktor.client.engine.HttpClientEngine

/**
 * `--backend-type`, `--backend-url` and `--allow-localhost` of the `cache` subcommands that talk to the remote cache.
 * `cache.remote` of the config comes first, the flags override its backend and URL. A `--backend-url` other than
 * `cache.remote.url` gets none of the config's credentials (`auth`, S3 keys, ambient AWS credentials).
 */
internal class RemoteCacheOptions : OptionGroup() {
    private val backendType by option(
        "--backend-type",
        help = "Remote backend type: http, s3, or gcs (default: cache.remote.backend, else $DEFAULT_BACKEND_TYPE)",
    )

    private val backendUrl by option("--backend-url", help = "Remote backend URL (default: cache.remote.url)")

    private val allowLocalhost by option(
        "--allow-localhost",
        help = "Allow a localhost or private-IP backend URL over HTTP (disables SSRF protection)",
    ).flag()

    /** The backend type and URL the flags and [configured] give, for display; each null when neither sets it. */
    fun describe(configured: RemoteCacheConfig?): Pair<String?, String?> =
        (backendType ?: configured?.backend) to (backendUrl ?: configured?.url)

    /** [configured] (`cache.remote` of the config) with the flags applied; null when neither gives a URL. */
    fun resolve(
        configured: RemoteCacheConfig?,
        logger: Logger,
    ): RemoteCacheConfig? {
        val url = backendUrl ?: configured?.url ?: return null
        // the config's limits apply; its auth and S3 credentials only to its own URL
        val base = when {
            configured == null -> RemoteCacheConfig(backend = DEFAULT_BACKEND_TYPE, url = url)
            sameUrl(configured.url, url) -> configured
            else -> withoutCredentials(configured, logger)
        }
        return base.copy(backend = backendType ?: base.backend, url = url)
    }

    /**
     * Connects to [remote] (from [resolve]) under the loaded config [stage] (null when none was read); ambient AWS
     * credentials sign only for the configured URL, read from [awsEnv]. [engine] is for tests.
     */
    fun connect(
        stage: ConfigLoadStage?,
        remote: RemoteCacheConfig,
        logger: Logger,
        awsEnv: (String) -> String?,
        engine: HttpClientEngine? = null,
    ): RemoteCacheConnection {
        val configuredUrl = stage
            ?.config
            ?.cache
            ?.remote
            ?.url
        val chain = if (configuredUrl != null && sameUrl(configuredUrl, remote.url)) AwsCredentialChain(logger, awsEnv) else null
        return connectRemoteCache(
            remote,
            logger,
            allowLocalhost || stage?.contexts?.allowLocalhost == true,
            chain,
            stage?.let { PipelineInfrastructure.remoteRegionSet(it.configLayers) } ?: false,
            engine,
        )
    }

    private fun withoutCredentials(
        configured: RemoteCacheConfig,
        logger: Logger,
    ): RemoteCacheConfig {
        val stripped = configured.copy(auth = null, accessKeyId = null, secretAccessKey = null, sessionToken = null)
        if (stripped != configured) {
            logger.warn("--backend-url differs from cache.remote.url: the config's auth and S3 credentials were not applied")
        }
        return stripped
    }

    private companion object {
        const val DEFAULT_BACKEND_TYPE = "http"

        /** Equal after trimming a trailing `/` and lowercasing the scheme and host (the path stays case-sensitive). */
        fun sameUrl(
            a: String,
            b: String,
        ): Boolean = normalizeUrl(a) == normalizeUrl(b)

        private fun normalizeUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val pathStart = trimmed.indexOf('/', trimmed.indexOf("://").let { if (it < 0) 0 else it + "://".length })
            return if (pathStart < 0) trimmed.lowercase() else trimmed.take(pathStart).lowercase() + trimmed.drop(pathStart)
        }
    }
}
