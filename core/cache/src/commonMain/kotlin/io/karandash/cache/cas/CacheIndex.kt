package io.karandash.cache.cas

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.safeName
import io.karandash.platform.writeTextAtomic
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Mutable index entry pointing to an immutable CAS blob.
 *
 * Index files are small JSON written atomically (temp + rename).
 * They store the digest of the cached content plus metadata like
 * ETag, expiration, and consumer-specific fields.
 */
@Serializable
data class CacheIndexEntry(
    /** SHA-256 hex digest of the CAS blob. */
    val digest: String,
    /** HTTP ETag for conditional revalidation (304 Not Modified). */
    val etag: String? = null,
    /** HTTP Last-Modified header value. */
    val lastModified: String? = null,
    /** Expiration timestamp (epoch milliseconds). */
    val expiresAt: Long,
    /** Creation timestamp (epoch milliseconds). */
    val createdAt: Long,
    /** Consumer-specific metadata (e.g. provider name, request params). */
    val metadata: JsonElement = JsonNull,
) {
    /** Check if this entry has expired. */
    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs > expiresAt

    /** Convert digest string to [CasDigest]. */
    fun casDigest(): CasDigest = CasDigest(digest)
}

/**
 * Reads and writes [CacheIndexEntry] files atomically.
 *
 * Index files live under `{indexDir}/{namespace}/{key}.json`.
 * Writes use temp-file + atomic rename for process safety.
 */
class CacheIndexStore(
    private val indexDir: Path,
    private val fs: FileSystem = SystemFileSystem,
    private val logger: Logger = NoOpLogger,
) {
    /** Read an index entry, or null if not found or corrupted. */
    fun read(
        namespace: String,
        key: String
    ): CacheIndexEntry? {
        val path = indexPath(namespace, key)
        if (!fs.exists(path)) return null
        return try {
            val json = fs.source(path).buffered().use { it.readString() }
            KarandashJson.Default.decodeFromString<CacheIndexEntry>(json)
        } catch (e: IOException) {
            logger.debug("CacheIndex: read failed for $namespace/$key: ${e.message}")
            null
        } catch (e: SerializationException) {
            logger.debug("CacheIndex: read failed for $namespace/$key: ${e.message}")
            null
        }
    }

    /** Write an index entry atomically. */
    fun write(
        namespace: String,
        key: String,
        entry: CacheIndexEntry
    ) {
        val dir = Path(indexDir, namespace)
        if (!fs.exists(dir)) {
            try {
                createDirectories(dir)
            } catch (e: IOException) {
                if (!fs.exists(dir)) throw e
                logger.debug("CacheIndex: createDirectories race for $namespace: ${e.message}")
            }
        }
        val path = indexPath(namespace, key)
        path.writeTextAtomic(KarandashJson.Default.encodeToString(CacheIndexEntry.serializer(), entry), fs)
    }

    /** Delete an index entry. Returns true if it existed. */
    fun delete(
        namespace: String,
        key: String
    ): Boolean {
        val path = indexPath(namespace, key)
        if (!fs.exists(path)) return false
        try {
            fs.delete(path)
        } catch (e: IOException) {
            logger.debug("CacheIndex: delete failed for $namespace/$key: ${e.message}")
            return false
        }
        return true
    }

    /** List all keys in a namespace. */
    fun listKeys(namespace: String): List<String> {
        val dir = Path(indexDir, namespace)
        if (!fs.exists(dir)) return emptyList()
        return try {
            fs
                .list(dir)
                .filter { it.safeName.endsWith(INDEX_EXTENSION) }
                .map { it.safeName.removeSuffix(INDEX_EXTENSION) }
        } catch (e: IOException) {
            logger.debug("CacheIndex: listKeys failed for $namespace: ${e.message}")
            emptyList()
        }
    }

    /** Resolve the file path for an index entry. */
    fun indexPath(
        namespace: String,
        key: String
    ): Path {
        require(".." !in namespace) { "Namespace must not contain '..': $namespace" }
        require(".." !in key) { "Key must not contain '..': $key" }
        return Path(indexDir, namespace, "$key$INDEX_EXTENSION")
    }

    private companion object {
        const val INDEX_EXTENSION = ".json"
    }
}
