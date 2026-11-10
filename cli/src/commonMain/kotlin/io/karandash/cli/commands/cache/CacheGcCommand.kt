package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.api.support.suspendCatching
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cache.cas.CacheGarbageCollector
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoadStage
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.safeName
import io.ktor.client.engine.HttpClientEngine
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Runs garbage collection on the local CAS cache.
 *
 * Removes expired index entries and orphaned CAS blobs that are no longer
 * referenced by any index entry. Reports the number of entries removed
 * and bytes reclaimed. With `--remote`, entries older than `cache.remote.ttl` expire too, and every blob removed
 * locally is deleted on the remote backend (`cache.remote` of the config, or the `--backend-*` flags).
 *
 * Usage:
 * ```
 * karandash cache gc
 * karandash cache gc --cache-dir ~/.karandash/cache
 * karandash cache gc --remote
 * ```
 */
class CacheGcCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
    /** HTTP engine for the remote backend client; null uses the platform engine (tests pass a MockEngine). */
    private val engine: HttpClientEngine? = null,
    /** Environment the AWS credential chain reads; tests pass a fake. */
    private val awsEnv: (String) -> String? = PlatformEnvironment::getEnv,
) : CliCommandBase("gc", bootstrap) {
    override fun help(context: Context) = "Run garbage collection on the local CAS cache"

    private val remote by option(
        "--remote",
        help = "Also delete the removed blobs on the remote backend and expire entries older than cache.remote.ttl",
    ).flag()

    private val remoteOptions by RemoteCacheOptions()

    private val dirOptions by CacheDirOptions()

    override suspend fun execute(): Int {
        val (resolvedDir, stage) =
            dirOptions.resolveWithStage(bootstrap, alwaysLoad = remote) { echo(it, err = true) } ?: return ExitCodes.CONFIG_ERROR
        val remoteConfig = if (remote) remoteOptions.resolve(stage?.config?.cache?.remote, logger) else null
        if (remote && remoteConfig == null) {
            echo("Error: --remote needs cache.remote.url in the config or --backend-url", err = true)
            return ExitCodes.CONFIG_ERROR
        }
        val indexDir = Path(resolvedDir, CacheManager.INDEX_DIR)

        val namespaces = try {
            discoverNamespaces(indexDir)
        } catch (e: IOException) {
            echo("[ERROR] Cannot read cache index directory $indexDir: ${e.message}", err = true)
            return ExitCodes.EXECUTION_ERROR
        }
        if (namespaces.isEmpty()) {
            echo("No cache namespaces found at $indexDir")
            return ExitCodes.SUCCESS
        }

        echo("Running GC for namespaces: ${namespaces.joinToString(", ")}")

        val gc = CacheGarbageCollector(baseDir = resolvedDir)
        val expired = gc.collectExpired(namespaces, remoteConfig?.ttl?.millis)
        val orphans = gc.collectOrphans(namespaces)

        val totalExpired = expired.expiredIndexesRemoved
        val totalOrphans = expired.orphanBlobsRemoved + orphans.orphanBlobsRemoved
        val totalBytes = expired.bytesReclaimed + orphans.bytesReclaimed

        echo("GC complete:")
        echo("  Expired indexes removed : $totalExpired")
        echo("  Orphan blobs removed    : $totalOrphans")
        echo("  Bytes reclaimed         : ${formatBytes(totalBytes)}")
        if (remoteConfig == null) return ExitCodes.SUCCESS
        val failed = deleteRemote(stage, remoteConfig, expired.deletedDigests + orphans.deletedDigests)
        return if (failed > 0) ExitCodes.EXECUTION_ERROR else ExitCodes.SUCCESS
    }

    /** Deletes [digests] on the remote backend; returns the number of failed deletes (each logged). */
    private suspend fun deleteRemote(
        stage: ConfigLoadStage?,
        remoteConfig: RemoteCacheConfig,
        digests: List<String>,
    ): Int {
        val failed = remoteOptions.connect(stage, remoteConfig, logger, awsEnv, engine).use { backend ->
            digests.count { hex ->
                suspendCatching { backend.delete(CasDigest(hex)) }
                    .onFailure { logger.warn("Remote delete of $hex failed: ${it.message}") }
                    .isFailure
            }
        }
        echo("  Remote blobs deleted    : ${digests.size - failed}")
        if (failed > 0) echo("  Remote deletes failed   : $failed", err = true)
        return failed
    }

    private fun discoverNamespaces(indexDir: Path): List<String> {
        if (!SystemFileSystem.exists(indexDir)) return emptyList()
        return SystemFileSystem
            .list(indexDir)
            .filter { !it.safeName.startsWith(".") }
            .map { it.safeName }
    }
}
