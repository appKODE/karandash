package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.karandash.api.support.suspendCatching
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.remote.RemoteCacheBackend
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.safeName
import io.ktor.client.engine.HttpClientEngine
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Pushes all local CAS blobs to the configured remote cache backend.
 *
 * Reads the cache directory, enumerates all blobs in the CAS shard layout,
 * and uploads any blobs not already present on the remote. Skips blobs that
 * exceed the configured [io.karandash.cache.remote.RemoteCacheConfig.maxBlobSize].
 *
 * The remote backend is `cache.remote` from the config (`--config`, else the discovered `.karandash/config.*`),
 * so its `auth`, S3 credentials, `prefix` and `max-blob-size` apply as in a validation run;
 * `--backend-type` and `--backend-url` override its backend and URL, or stand alone without a config. A
 * `--backend-url` other than `cache.remote.url` gets none of the config's credentials (`auth`, S3 keys).
 *
 * Usage:
 * ```
 * karandash cache push
 * karandash cache push --config ci/karandash.yaml
 * karandash cache push --backend-type http --backend-url https://cache.example.com
 * karandash cache push --backend-url http://localhost:8080/cache --allow-localhost
 * ```
 */
class CachePushCommand(
    /** Must have its plugins loaded: the config is parsed through its format registry. */
    bootstrap: PluginBootstrap,
    /** HTTP engine for the backend client; null uses the platform engine (tests pass a MockEngine). */
    private val engine: HttpClientEngine? = null,
    /** Environment the AWS credential chain reads (keys, `AWS_PROFILE`, `HOME`); tests pass a fake. */
    private val awsEnv: (String) -> String? = PlatformEnvironment::getEnv,
) : CliCommandBase("push", bootstrap) {
    override fun help(context: Context) = "Push local CAS blobs to the remote cache backend"

    private val remoteOptions by RemoteCacheOptions()

    private val dirOptions by CacheDirOptions()

    override suspend fun execute(): Int {
        val (resolvedDir, stage) =
            dirOptions.resolveWithStage(bootstrap, alwaysLoad = true) { echo(it, err = true) } ?: return ExitCodes.CONFIG_ERROR
        val remoteConfig = remoteOptions.resolve(stage?.config?.cache?.remote, logger)
        if (remoteConfig == null) {
            echo("Error: --backend-url (or cache.remote.url in the config) is required for cache push", err = true)
            return ExitCodes.CONFIG_ERROR
        }
        val blobsDir = Path(resolvedDir, CacheManager.CAS_BLOBS_DIR)
        if (!SystemFileSystem.exists(blobsDir)) {
            echo("No local CAS blobs found at $blobsDir")
            return ExitCodes.SUCCESS
        }
        return remoteOptions.connect(stage, remoteConfig, logger, awsEnv, engine).use { pushAll(blobsDir, remoteConfig, it) }
    }

    private suspend fun pushAll(
        blobsDir: Path,
        remoteConfig: RemoteCacheConfig,
        backend: RemoteCacheBackend,
    ): Int {
        val blobStore = CasBlobStore(blobsDir)
        var pushed = 0
        var skipped = 0
        var failed = 0

        val shards = try {
            SystemFileSystem.list(blobsDir).filter { !it.safeName.startsWith(".") }
        } catch (e: IOException) {
            echo("Error reading CAS directory: ${e.message}", err = true)
            return ExitCodes.EXECUTION_ERROR
        }

        for (shardDir in shards) {
            val blobs = try {
                SystemFileSystem.list(shardDir).filter { !it.safeName.startsWith(".") }
            } catch (e: IOException) {
                echo("Error reading CAS shard $shardDir: ${e.message}", err = true)
                failed++
                continue
            }
            for (blobFile in blobs) {
                val hex = shardDir.safeName + blobFile.safeName
                val digest = suspendCatching { CasDigest(hex) }.getOrNull() ?: continue
                val content = blobStore.get(digest)
                if (content == null || content.size.toLong() > remoteConfig.maxBlobSize.bytes) {
                    skipped++
                    continue
                }
                val alreadyExists = suspendCatching { backend.exists(digest) }.getOrElse { false }
                if (alreadyExists) {
                    skipped++
                    continue
                }
                val pushResult = suspendCatching { backend.push(digest, content) }
                if (pushResult.getOrDefault(false)) {
                    pushed++
                } else {
                    failed++
                    // a refused blob (non-2xx) was already logged by the backend
                    pushResult.exceptionOrNull()?.let { logger.warn("Push of $hex failed: ${it.message}") }
                }
            }
        }

        echo("Cache push complete: $pushed pushed, $skipped skipped, $failed failed")
        return if (failed > 0) ExitCodes.EXECUTION_ERROR else ExitCodes.SUCCESS
    }
}
