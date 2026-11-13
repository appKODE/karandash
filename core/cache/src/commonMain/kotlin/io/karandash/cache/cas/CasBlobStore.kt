package io.karandash.cache.cas

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.createDirectories
import io.karandash.platform.writeBytesAtomic
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Content-addressable blob store using SHA-256 digests.
 *
 * Blobs are stored in a sharded directory layout: `{baseDir}/cas/{shard}/{remainder}`.
 * Writes use temp-file + atomic rename for process safety. Same content always
 * produces the same digest - concurrent writes are idempotent.
 *
 * @param casDir Root directory for CAS blobs (e.g. `~/.karandash/cache/v1/cas`).
 * @param fs File system abstraction (defaults to [SystemFileSystem]).
 * @param logger Logger for debug/warning messages.
 */
class CasBlobStore(
    private val casDir: Path,
    private val fs: FileSystem = SystemFileSystem,
    private val logger: Logger = NoOpLogger,
) {
    /**
     * Write content to CAS. Idempotent - same content always produces same path.
     *
     * @return The digest of the stored content.
     */
    fun put(content: ByteArray): CasDigest {
        val digest = CasDigest.of(content)
        val blobPath = blobPath(digest)
        if (fs.exists(blobPath)) return digest

        val shardDir = Path(casDir, digest.shard)
        ensureDirectory(shardDir)

        try {
            blobPath.writeBytesAtomic(content, fs)
        } catch (e: IOException) {
            // Another process may have won the race: the blob exists, so the failed write is redundant
            if (!fs.exists(blobPath)) throw e
        }
        logger.debug("CAS put: ${digest.hex.take(DIGEST_LOG_LENGTH)} (${content.size} bytes)")
        return digest
    }

    /**
     * Lockless read - blob is immutable once written.
     *
     * @return Blob content, or null if the digest is not stored.
     */
    fun get(digest: CasDigest): ByteArray? {
        val path = blobPath(digest)
        if (!fs.exists(path)) return null
        return try {
            fs.source(path).buffered().use { it.readByteArray() }
        } catch (e: IOException) {
            logger.debug("CAS read failed for ${digest.hex.take(DIGEST_LOG_LENGTH)}: ${e.message}")
            null
        }
    }

    /** Check if a blob exists without reading it. */
    fun exists(digest: CasDigest): Boolean = fs.exists(blobPath(digest))

    /** Delete a blob. Returns true if it was present. */
    fun delete(digest: CasDigest): Boolean {
        val path = blobPath(digest)
        if (!fs.exists(path)) return false
        try {
            fs.delete(path)
        } catch (e: IOException) {
            logger.debug("CAS delete failed for ${digest.hex.take(DIGEST_LOG_LENGTH)}: ${e.message}")
            return false
        }
        return true
    }

    /** Resolve the file path for a given digest. */
    fun blobPath(digest: CasDigest): Path = Path(casDir, digest.shard, digest.remainder)

    private fun ensureDirectory(dir: Path) {
        if (fs.exists(dir)) return
        try {
            createDirectories(dir)
        } catch (e: IOException) {
            // a concurrent process may have created it; otherwise the write would fail later with a vaguer error
            if (!fs.exists(dir)) throw e
        }
        // some platforms report a failed mkdir as false, not an exception
        if (!fs.exists(dir)) throw IOException("Failed to create CAS directory $dir")
    }

    private companion object {
        const val DIGEST_LOG_LENGTH = 12
    }
}
