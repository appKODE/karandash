package io.karandash.cacheserver

import io.karandash.cache.cas.CasDigest
import io.karandash.crypto.CryptoProvider
import io.karandash.crypto.toHexString
import io.karandash.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** One blob: [digest] inside the cache [namespace] (the client's `cache.remote.prefix`, `""` for none). */
class BlobKey(
    val namespace: String,
    val digest: CasDigest,
) {
    /** `<namespace hash>/<shard>/<remainder>`: a hashed namespace directory, so no prefix can escape the data dir. */
    fun relativePath(): String = "${namespaceDir(namespace)}/${digest.shard}/${digest.remainder}"

    private companion object {
        const val NAMESPACE_DIR_LEN = 16

        fun namespaceDir(namespace: String): String =
            CryptoProvider.sha256(namespace.encodeToByteArray()).toHexString().take(NAMESPACE_DIR_LEN)
    }
}

/**
 * Content-addressed blobs on disk under `<root>/ns`, bounded to [maxBytes] by LRU eviction.
 *
 * A write goes to a temp file under `<root>/tmp` and is renamed into place atomically, so a reader sees the old blob
 * or the new one, never a torn one; the same key written twice keeps the last writer's (identical) content. Reads run
 * without the lock (an open file survives a concurrent replace or eviction); renames, deletes and the LRU index run
 * under [mutex]. The LRU order starts from file modification times on startup.
 *
 * @lifecycle application-scoped -- one per server, safe for concurrent requests
 */
class BlobStore(
    root: Path,
    private val maxBytes: Long,
    private val logger: Logger,
    private val metrics: CacheMetrics = CacheMetrics(),
) {
    private val blobsDir = root.resolve(BLOBS_DIR)
    private val tmpDir = root.resolve(TMP_DIR)

    // limit: one global lock around rename + index; per-shard locks if PUT throughput ever matters
    private val mutex = Mutex()

    /** Relative path to size, least recently used first. Guarded by [mutex]. */
    private val lru = LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val totalBytes = MutableStateFlow(0L)

    init {
        Files.createDirectories(blobsDir)
        Files.createDirectories(tmpDir)
        Files.list(tmpDir).use { stale -> stale.forEach { it.deleteIfExists() } }
        Files.walk(blobsDir).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .map { it to it.getLastModifiedTime() }
                .toList()
                .sortedBy { it.second }
                .forEach { (path, _) -> record(blobsDir.relativize(path).toString(), path.fileSize()) }
        }
        evictOverLimit(keep = null)
        logger.info("Cache store at $root: ${lru.size} blobs, ${totalBytes.value} bytes (max $maxBytes)")
    }

    /** Bytes currently stored. */
    fun size(): Long = totalBytes.value

    /** The blob's content, or null when it is not stored. */
    suspend fun read(key: BlobKey): ByteArray? {
        val relative = key.relativePath()
        val bytes = withContext(Dispatchers.IO) {
            try {
                blobsDir.resolve(relative).readBytes()
            } catch (e: NoSuchFileException) {
                logger.debug("Cache miss for ${e.file}")
                null
            }
        }
        if (bytes != null) mutex.withLock { lru[relative] }
        return bytes
    }

    /** Whether the blob is stored. */
    suspend fun contains(key: BlobKey): Boolean = mutex.withLock { lru.containsKey(key.relativePath()) }

    /** Stores [content] under [key] (temp file, then atomic rename), then evicts least recently used blobs over the limit. */
    suspend fun write(
        key: BlobKey,
        content: ByteArray,
    ) {
        val relative = key.relativePath()
        val target = blobsDir.resolve(relative)
        withContext(Dispatchers.IO) {
            // limit: no fsync; a blob torn by a power loss fails the client's digest check and reads as a miss
            val tmp = Files.createTempFile(tmpDir, "put-", ".tmp")
            try {
                tmp.writeBytes(content)
                mutex.withLock {
                    Files.createDirectories(target.parent)
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    lru.remove(relative)?.let { old -> totalBytes.update { it - old } }
                    record(relative, content.size.toLong())
                    evictOverLimit(keep = relative)
                }
            } finally {
                tmp.deleteIfExists()
            }
        }
    }

    /** Removes the blob; true when it was stored. */
    suspend fun delete(key: BlobKey): Boolean {
        val relative = key.relativePath()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val size = lru.remove(relative)
                blobsDir.resolve(relative).deleteIfExists()
                size?.let { removed -> totalBytes.update { it - removed } }
                size != null
            }
        }
    }

    private fun record(
        relative: String,
        size: Long,
    ) {
        lru[relative] = size
        totalBytes.update { it + size }
    }

    /** Drops least recently used blobs until the store fits [maxBytes]; never [keep] (the blob just written). */
    private fun evictOverLimit(keep: String?) {
        val iterator = lru.entries.iterator()
        while (totalBytes.value > maxBytes && iterator.hasNext()) {
            val (relative, size) = iterator.next()
            if (relative == keep) continue
            try {
                blobsDir.resolve(relative).deleteIfExists()
            } catch (e: IOException) {
                logger.warn("Cache eviction could not delete $relative: ${e.message}")
            }
            iterator.remove()
            totalBytes.update { it - size }
            metrics.evictions.update { it + 1 }
        }
    }

    private companion object {
        const val BLOBS_DIR = "ns"
        const val TMP_DIR = "tmp"
        const val INITIAL_CAPACITY = 1024
        const val LOAD_FACTOR = 0.75f
    }
}
