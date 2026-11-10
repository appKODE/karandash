package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.platform.safeName
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Invalidates every cache namespace (listed with its [io.karandash.cache.CacheCategory]), then deletes all CAS
 * blobs, index entries and the last run's stats from the local cache.
 *
 * Usage:
 * ```
 * karandash cache clear
 * karandash cache clear --cache-dir ~/.karandash/cache
 * ```
 */
class CacheClearCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : CliCommandBase("clear", bootstrap) {
    override fun help(context: Context) = "Delete all local CAS cache data"

    private val dirOptions by CacheDirOptions()

    override suspend fun execute(): Int {
        val resolvedDir = dirOptions.resolve(bootstrap) { echo(it, err = true) } ?: return ExitCodes.CONFIG_ERROR
        val casDir = Path(resolvedDir, CacheManager.CAS_BLOBS_DIR)
        val indexDir = Path(resolvedDir, CacheManager.INDEX_DIR)

        echo("Cache cleared:")
        invalidateNamespaces(resolvedDir, indexDir)
        val failures = mutableListOf<Path>()
        val blobsDeleted = deleteAllFiles(casDir, failures)
        deleteAllFiles(indexDir, failures) // index files were already removed by invalidateNamespaces; this drops leftovers

        deleteOrRecord(Path(resolvedDir, CacheManager.RUN_STATS_FILE), failures, mustExist = false)
        echo("  Blobs deleted   : $blobsDeleted")
        if (failures.isEmpty()) return ExitCodes.SUCCESS
        echo("[ERROR] Could not delete ${failures.size} cache path(s), first: ${failures.first()}", err = true)
        return ExitCodes.EXECUTION_ERROR
    }

    /** Drops the index entries of every namespace under [indexDir] through [CacheManager.invalidateAll], listed by category. */
    private fun invalidateNamespaces(
        resolvedDir: Path,
        indexDir: Path,
    ) {
        if (!SystemFileSystem.exists(indexDir)) return
        val manager = CacheManager(cacheDir = resolvedDir)
        for (nsDir in listOrRecord(indexDir, mutableListOf())) {
            echo("  ${namespaceLabel(nsDir.safeName)} : ${manager.getCasCache(nsDir.safeName).keys().size} entries")
        }
        echo("  Entries invalidated : ${manager.invalidateAll()}")
    }

    /** Deletes every file under the shard directories of [baseDir]; paths that could not be removed go to [failures]. */
    private fun deleteAllFiles(
        baseDir: Path,
        failures: MutableList<Path>,
    ): Int {
        if (!SystemFileSystem.exists(baseDir)) return 0
        var deleted = 0
        for (subDir in listOrRecord(baseDir, failures)) {
            for (file in listOrRecord(subDir, failures)) {
                if (deleteOrRecord(file, failures)) deleted++
            }
            deleteOrRecord(subDir, failures)
        }
        return deleted
    }

    private fun deleteOrRecord(
        path: Path,
        failures: MutableList<Path>,
        mustExist: Boolean = true,
    ): Boolean =
        try {
            SystemFileSystem.delete(path, mustExist)
            true
        } catch (_: IOException) {
            failures += path
            false
        }

    private fun listOrRecord(
        dir: Path,
        failures: MutableList<Path>,
    ): List<Path> =
        try {
            SystemFileSystem.list(dir).filter { !it.safeName.startsWith(".") }
        } catch (_: IOException) {
            failures += dir
            emptyList()
        }
}
