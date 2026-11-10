package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheCategory
import io.karandash.cache.CacheManager
import io.karandash.cache.cas.CacheIndexStore
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.platform.safeName
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.math.roundToInt

/**
 * Prints local CAS cache statistics: blob count, total size, index entries
 * per namespace, and orphaned blob count.
 *
 * Usage:
 * ```
 * karandash cache stats
 * karandash cache stats --cache-dir ~/.karandash/cache
 * ```
 */
class CacheStatsCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : CliCommandBase("stats", bootstrap) {
    override fun help(context: Context) = "Show local CAS cache statistics"

    private val dirOptions by CacheDirOptions()

    override suspend fun execute(): Int {
        val resolvedDir = dirOptions.resolve(bootstrap) { echo(it, err = true) } ?: return ExitCodes.CONFIG_ERROR
        val casDir = Path(resolvedDir, CacheManager.CAS_BLOBS_DIR)
        val indexDir = Path(resolvedDir, CacheManager.INDEX_DIR)

        echo("Local CAS Cache Statistics")
        echo("  Base dir : $resolvedDir")
        echo("")

        val unreadable = mutableListOf<Path>()
        val (blobCount, totalBytes) = countBlobs(casDir, unreadable)
        echo("Blob Store (${CacheManager.CAS_BLOBS_DIR}/)")
        echo("  Blobs      : $blobCount")
        echo("  Total size : ${formatBytes(totalBytes)}")
        echo("")

        val namespaceStats = countIndexEntries(indexDir, unreadable)
        echo("Index Store (${CacheManager.INDEX_DIR}/)")
        if (namespaceStats.isEmpty()) {
            echo("  (no namespaces)")
        } else {
            for ((ns, count) in namespaceStats) {
                echo("  ${namespaceLabel(ns)} : $count entries")
            }
        }
        echo("")
        echoLastRun(resolvedDir)

        val referencedDigests = collectReferencedDigests(indexDir, namespaceStats.keys, unreadable)
        val orphanCount = countOrphans(casDir, referencedDigests, unreadable)
        echo("Orphaned blobs : $orphanCount")
        if (unreadable.isEmpty()) return ExitCodes.SUCCESS
        echo("[ERROR] Statistics are incomplete: ${unreadable.size} unreadable path(s), first: ${unreadable.first()}", err = true)
        return ExitCodes.EXECUTION_ERROR
    }

    /** The hits and misses [CacheManager.saveRunStats] recorded for the last validation run, when there are any. */
    private fun echoLastRun(resolvedDir: Path) {
        val stats = CacheManager.readRunStats(resolvedDir) ?: return
        echo("Last run (${CacheManager.RUN_STATS_FILE})")
        for ((name, cache) in stats.perCache) {
            val pushed = if (cache.pushes + cache.pushFailures > 0) ", ${cache.pushes} pushed, ${cache.pushFailures} push failures" else ""
            val dedup = pushed + if (cache.deduplicatedWrites > 0) ", ${cache.deduplicatedWrites} deduplicated writes" else ""
            echo("  ${namespaceLabel(name)} : ${cache.hits} hits, ${cache.misses} misses, ${percent(cache.hitRate)} hit rate$dedup")
        }
        echo("  total : ${stats.totalHits} hits, ${stats.totalMisses} misses, ${percent(stats.hitRate)} hit rate")
        echo("")
    }

    private fun percent(rate: Double): String = "${(rate * PERCENT).roundToInt()}%"

    private fun countBlobs(
        casDir: Path,
        unreadable: MutableList<Path>,
    ): Pair<Int, Long> {
        if (!SystemFileSystem.exists(casDir)) return Pair(0, 0L)
        var count = 0
        var totalBytes = 0L
        for (shardDir in safeList(casDir, unreadable)) {
            for (blobFile in safeList(shardDir, unreadable)) {
                count++
                totalBytes += safeSize(blobFile, unreadable)
            }
        }
        return Pair(count, totalBytes)
    }

    private fun countIndexEntries(
        indexDir: Path,
        unreadable: MutableList<Path>,
    ): Map<String, Int> {
        if (!SystemFileSystem.exists(indexDir)) return emptyMap()
        val result = mutableMapOf<String, Int>()
        for (nsDir in safeList(indexDir, unreadable)) {
            val count = safeList(nsDir, unreadable).count { it.safeName.endsWith(".json") }
            result[nsDir.safeName] = count
        }
        return result
    }

    private fun collectReferencedDigests(
        indexDir: Path,
        namespaces: Set<String>,
        unreadable: MutableList<Path>,
    ): Set<String> {
        val store = CacheIndexStore(indexDir)
        val digests = mutableSetOf<String>()
        for (ns in namespaces) {
            for (indexFile in safeList(Path(indexDir, ns), unreadable).filter { it.safeName.endsWith(INDEX_EXTENSION) }) {
                val entry = store.read(ns, indexFile.safeName.removeSuffix(INDEX_EXTENSION))
                when {
                    entry != null -> digests += entry.digest

                    // an entry that does not decode references nothing, as for `cache gc`; one that is not a file is unreadable
                    SystemFileSystem.metadataOrNull(indexFile)?.isRegularFile != true -> unreadable += indexFile
                }
            }
        }
        return digests
    }

    private fun countOrphans(
        casDir: Path,
        referencedDigests: Set<String>,
        unreadable: MutableList<Path>,
    ): Int {
        if (!SystemFileSystem.exists(casDir)) return 0
        var orphans = 0
        for (shardDir in safeList(casDir, unreadable)) {
            for (blobFile in safeList(shardDir, unreadable)) {
                val digest = shardDir.safeName + blobFile.safeName
                if (digest !in referencedDigests) orphans++
            }
        }
        return orphans
    }

    private fun safeList(
        dir: Path,
        unreadable: MutableList<Path>,
    ): List<Path> =
        try {
            SystemFileSystem.list(dir).filter { !it.safeName.startsWith(".") }
        } catch (_: IOException) {
            unreadable += dir
            emptyList()
        }

    private fun safeSize(
        path: Path,
        unreadable: MutableList<Path>,
    ): Long =
        try {
            SystemFileSystem.metadataOrNull(path)?.size ?: 0L
        } catch (_: IOException) {
            unreadable += path
            0L
        }

    private companion object {
        const val PERCENT = 100
        const val INDEX_EXTENSION = ".json"
    }
}

/** [bytes] in the largest whole unit of B, KB, MB or GB that is at least 1 (1024-based). */
internal fun formatBytes(bytes: Long): String =
    when {
        bytes < BYTES_PER_KB -> "$bytes B"
        bytes < BYTES_PER_MB -> "${bytes / BYTES_PER_KB} KB"
        bytes < BYTES_PER_GB -> "${bytes / BYTES_PER_MB} MB"
        else -> "${bytes / BYTES_PER_GB} GB"
    }

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_GB = 1024L * 1024L * 1024L

/** [namespace] followed by the [CacheCategory] names it holds, e.g. `downloads (extends, plugins)`. */
internal fun namespaceLabel(namespace: String): String {
    val categories = CacheCategory.forNamespace(namespace)
    return if (categories.isEmpty()) namespace else "$namespace (${categories.joinToString { it.displayName }})"
}
