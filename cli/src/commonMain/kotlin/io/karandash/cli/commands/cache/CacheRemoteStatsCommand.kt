package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.karandash.api.support.suspendCatching
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
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
 * Prints remote cache backend configuration, whether the backend answers (one HEAD request), and local blob
 * store stats.
 *
 * Usage:
 * ```
 * karandash cache remote-stats --backend-type http --backend-url https://cache.example.com
 * karandash cache remote-stats
 * ```
 */
class CacheRemoteStatsCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
    /** HTTP engine for the reachability check; null uses the platform engine (tests pass a MockEngine). */
    private val engine: HttpClientEngine? = null,
    /** Environment the AWS credential chain reads; tests pass a fake. */
    private val awsEnv: (String) -> String? = PlatformEnvironment::getEnv,
) : CliCommandBase("remote-stats", bootstrap) {
    override fun help(context: Context) = "Show remote cache backend configuration, reachability and local blob counts"

    private val remoteOptions by RemoteCacheOptions()

    private val dirOptions by CacheDirOptions()

    override suspend fun execute(): Int {
        val (resolvedDir, stage) =
            dirOptions.resolveWithStage(bootstrap, alwaysLoad = false) { echo(it, err = true) } ?: return ExitCodes.CONFIG_ERROR
        val configured = stage?.config?.cache?.remote
        // the flags override cache.remote of the config
        val (type, url) = remoteOptions.describe(configured)
        echo("Remote Cache Configuration")
        echo("  Backend type : ${type ?: NO_BACKEND}")
        echo("  Backend URL  : ${url ?: "(not configured)"}")
        val remote = if (type != null) remoteOptions.resolve(configured, logger) else null
        if (remote != null) echo("  Reachable    : ${reachability(stage, remote)}")
        echo("")
        echo("Local Blob Store")

        val blobsDir = Path(resolvedDir, CacheManager.CAS_BLOBS_DIR)

        if (!SystemFileSystem.exists(blobsDir)) {
            echo("  Blobs        : 0 (directory does not exist)")
            return ExitCodes.SUCCESS
        }

        val unreadable = mutableListOf<Path>()
        val (blobCount, totalBytes) = countBlobs(blobsDir, unreadable)
        echo("  Base dir     : $blobsDir")
        echo("  Blobs        : $blobCount")
        echo("  Total size   : ${formatBytes(totalBytes)}")

        if (remote == null) {
            echo("")
            echo("No remote backend configured. Set cache.remote in the config, or use --backend-type and --backend-url.")
        }
        if (unreadable.isEmpty()) return ExitCodes.SUCCESS
        echo("[ERROR] Blob counts are incomplete: ${unreadable.size} unreadable path(s), first: ${unreadable.first()}", err = true)
        return ExitCodes.EXECUTION_ERROR
    }

    /** `yes` when one HEAD request for the empty blob gets an HTTP answer (found or not), else `no (<why>)`. */
    private suspend fun reachability(
        stage: ConfigLoadStage?,
        remote: RemoteCacheConfig,
    ): String =
        suspendCatching { remoteOptions.connect(stage, remote, logger, awsEnv, engine).use { it.exists(CasDigest.of(ByteArray(0))) } }
            .fold({ "yes" }, { "no (${it.message})" })

    private fun countBlobs(
        blobsDir: Path,
        unreadable: MutableList<Path>,
    ): Pair<Int, Long> {
        var count = 0
        var totalBytes = 0L
        val shards = try {
            SystemFileSystem.list(blobsDir).filter { !it.safeName.startsWith(".") }
        } catch (_: IOException) {
            unreadable += blobsDir
            return Pair(0, 0L)
        }
        for (shardDir in shards) {
            val blobs = try {
                SystemFileSystem.list(shardDir).filter { !it.safeName.startsWith(".") }
            } catch (_: IOException) {
                unreadable += shardDir
                continue
            }
            for (blobFile in blobs) {
                count++
                totalBytes += try {
                    SystemFileSystem.metadataOrNull(blobFile)?.size ?: 0L
                } catch (_: IOException) {
                    unreadable += blobFile
                    0L
                }
            }
        }
        return Pair(count, totalBytes)
    }

    private companion object {
        const val NO_BACKEND = "none"
    }
}
