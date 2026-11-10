package io.karandash.cache.cas

import io.karandash.cache.CacheManager
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.safeName
import kotlinx.io.IOException
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Garbage collector for the CAS cache.
 *
 * Two collection modes:
 * 1. [collectExpired] - remove expired index entries and their unreferenced CAS blobs
 * 2. [collectOrphans] - scan all CAS blobs and remove those not referenced by any index
 * 3. [evictOverSize] - drop the oldest entries until the referenced blobs fit `cache.maxCacheSize`
 *
 * Safe for concurrent execution in the sense that nothing is corrupted: double deletion is idempotent, and a blob
 * deleted under a concurrent run's fresh index entry costs that run a lost hit ([CasCache.get] drops the entry).
 */
class CacheGarbageCollector(
    private val baseDir: Path,
    private val fs: FileSystem = SystemFileSystem,
    private val logger: Logger = NoOpLogger,
) {
    private val indexStore = CacheIndexStore(Path(baseDir, CacheManager.INDEX_DIR), fs, logger)

    /**
     * Remove expired index entries and delete their CAS blobs if unreferenced.
     *
     * @param maxAgeMillis when set, an entry created longer ago than this also counts as expired (`cache.remote.ttl`
     *   for `cache gc --remote`).
     */
    @OptIn(ExperimentalTime::class)
    fun collectExpired(
        namespaces: List<String>,
        maxAgeMillis: Long? = null,
    ): GcResult {
        val now = Clock.System.now().toEpochMilliseconds()
        var expiredRemoved = 0
        val referencedDigests = mutableSetOf<String>()

        for (namespace in namespaces) {
            for (key in indexStore.listKeys(namespace)) {
                val entry = indexStore.read(namespace, key) ?: continue
                if (entry.isExpired(now) || (maxAgeMillis != null && now - entry.createdAt > maxAgeMillis)) {
                    indexStore.delete(namespace, key)
                    expiredRemoved++
                } else {
                    referencedDigests.add(entry.digest)
                }
            }
        }

        // blobs of a namespace this run did not use (the `downloads` of the config pipeline) stay referenced
        referencedDigests += otherNamespaceDigests(namespaces)

        // Try to remove blobs for expired entries (only if no other index references them)
        val result = deleteUnreferenced(referencedDigests).copy(expiredIndexesRemoved = expiredRemoved)
        logger.debug(
            "CAS GC: ${result.expiredIndexesRemoved} expired, ${result.orphanBlobsRemoved} orphans, ${result.bytesReclaimed} bytes"
        )
        return result
    }

    /**
     * Scan all CAS blobs and remove those not referenced by any index entry in any namespace.
     *
     * Concurrency as for the class: a race loses a cache hit, never corrupts an entry.
     *
     * @param namespaces All namespaces whose index entries are considered live roots.
     * @return [GcResult] with [GcResult.orphanBlobsRemoved] populated and [GcResult.expiredIndexesRemoved] = 0.
     */
    fun collectOrphans(namespaces: List<String>): GcResult {
        val referencedDigests = mutableSetOf<String>()
        for (namespace in namespaces) {
            for (key in indexStore.listKeys(namespace)) {
                val entry = indexStore.read(namespace, key) ?: continue
                referencedDigests.add(entry.digest)
            }
        }
        referencedDigests += otherNamespaceDigests(namespaces)

        val result = deleteUnreferenced(referencedDigests)
        logger.debug("CAS orphan GC: ${result.orphanBlobsRemoved} orphans, ${result.bytesReclaimed} bytes")
        return result
    }

    /**
     * Digests of the live entries of every namespace on disk that is not in [namespaces]: a run only knows the
     * namespaces it used, but the blob store is shared, so another namespace's blobs are not orphans.
     */
    private fun otherNamespaceDigests(namespaces: List<String>): Set<String> {
        val indexDir = Path(baseDir, CacheManager.INDEX_DIR)
        if (!fs.exists(indexDir)) return emptySet()
        val now = Clock.System.now().toEpochMilliseconds()
        return safeList(indexDir)
            .map { it.safeName }
            .filter { it !in namespaces }
            .flatMap { ns -> indexStore.listKeys(ns).mapNotNull { key -> indexStore.read(ns, key) } }
            .filter { !it.isExpired(now) }
            .map { it.digest }
            .toSet()
    }

    /** Deletes every CAS blob outside [referencedDigests]; the result lists them in [GcResult.deletedDigests]. */
    private fun deleteUnreferenced(referencedDigests: Set<String>): GcResult {
        val blobStore = CasBlobStore(Path(baseDir, CacheManager.CAS_BLOBS_DIR), fs, logger)
        val casDir = Path(baseDir, CacheManager.CAS_BLOBS_DIR)
        val deleted = mutableListOf<String>()
        var bytesReclaimed = 0L
        if (fs.exists(casDir)) {
            for (shardDir in safeList(casDir)) {
                for (blobFile in safeList(shardDir)) {
                    val digest = shardDir.safeName + blobFile.safeName
                    if (digest in referencedDigests) continue
                    val size = blobSize(blobFile)
                    if (blobStore.delete(CasDigest(digest))) {
                        deleted += digest
                        bytesReclaimed += size
                    }
                }
            }
        }
        return GcResult(
            expiredIndexesRemoved = 0,
            orphanBlobsRemoved = deleted.size,
            bytesReclaimed = bytesReclaimed,
            deletedDigests = deleted
        )
    }

    /**
     * Removes the oldest index entries (by [CacheIndexEntry.createdAt]) in [namespaces], deleting each blob once
     * no remaining entry references it, until the referenced blobs total at most [maxBytes]. A blob a live entry
     * of a namespace outside [namespaces] references is never deleted, only its entry here.
     *
     * @return [GcResult] with evicted entries in [GcResult.expiredIndexesRemoved] and deleted blobs in
     *   [GcResult.orphanBlobsRemoved].
     */
    fun evictOverSize(
        namespaces: List<String>,
        maxBytes: Long,
    ): GcResult {
        val entries = namespaces
            .flatMap { ns -> indexStore.listKeys(ns).mapNotNull { key -> indexStore.read(ns, key)?.let { Triple(ns, key, it) } } }
            .sortedBy { it.third.createdAt }
        val blobStore = CasBlobStore(Path(baseDir, CacheManager.CAS_BLOBS_DIR), fs, logger)
        // a blob another namespace still references is not this run's to delete (the blob store is shared)
        val pinned = otherNamespaceDigests(namespaces)
        val refCounts = entries.groupingBy { it.third.digest }.eachCount().toMutableMap()
        val sizes = refCounts.keys.associateWith { blobSize(blobStore.blobPath(CasDigest(it))) }
        var total = sizes.values.sum()
        var evicted = 0
        var blobsRemoved = 0
        var bytesReclaimed = 0L
        for ((ns, key, entry) in entries) {
            if (total <= maxBytes) break
            indexStore.delete(ns, key)
            evicted++
            val remaining = refCounts.getValue(entry.digest) - 1
            refCounts[entry.digest] = remaining
            if (remaining == 0 && entry.digest !in pinned && blobStore.delete(entry.casDigest())) {
                val size = sizes.getValue(entry.digest)
                total -= size
                bytesReclaimed += size
                blobsRemoved++
            }
        }
        if (evicted > 0) {
            logger.info("Cache over cache.maxCacheSize ($maxBytes bytes): evicted $evicted oldest entries, reclaimed $bytesReclaimed bytes")
        }
        return GcResult(expiredIndexesRemoved = evicted, orphanBlobsRemoved = blobsRemoved, bytesReclaimed = bytesReclaimed)
    }

    private fun safeList(dir: Path): List<Path> =
        try {
            fs.list(dir).filter { !it.safeName.startsWith(".") }
        } catch (e: IOException) {
            logger.warn("Cache GC: cannot list $dir, skipping it: ${e.message}")
            emptyList()
        }

    private fun blobSize(path: Path): Long =
        try {
            fs.metadataOrNull(path)?.size ?: 0L
        } catch (e: IOException) {
            logger.warn("Cache GC: cannot read size of $path, counting 0 bytes: ${e.message}")
            0L
        }
}

/**
 * Result of a garbage collection run. [deletedDigests]: hex digests of the blobs [CacheGarbageCollector.collectExpired]
 * and [CacheGarbageCollector.collectOrphans] deleted (`cache gc --remote` deletes them on the remote too).
 */
@Serializable
data class GcResult(
    val expiredIndexesRemoved: Int,
    val orphanBlobsRemoved: Int,
    val bytesReclaimed: Long,
    val deletedDigests: List<String> = emptyList(),
)
