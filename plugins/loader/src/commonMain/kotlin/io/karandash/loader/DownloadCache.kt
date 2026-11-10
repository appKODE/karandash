package io.karandash.loader

import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.cache.cas.CasCache
import io.karandash.crypto.CryptoProvider
import io.karandash.models.support.KarandashJson
import io.karandash.platform.readBytes
import io.karandash.platform.writeBytes
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * CAS-backed download cache for remote plugin JARs.
 *
 * Stores downloaded plugin binaries in a [CasCache] with namespace `"downloads"`.
 * Each entry uses two keys:
 * - `"plugin-{hash}"` - binary content (the plugin artifact)
 * - `"plugin-{hash}-meta"` - JSON-encoded [DownloadMetadata] (url, checksum, extension)
 *
 * @param casCache CAS cache backed by the `"downloads"` namespace.
 * @param ttlMillis Time-to-live for cached entries. Defaults to 24 hours.
 * @param logger Logger for debug messages.
 */
class DownloadCache(
    private val casCache: CasCache,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val logger: Logger = NoOpLogger,
) {
    companion object {
        /** Default TTL: 24 hours. */
        const val DEFAULT_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L

        private const val KEY_PREFIX = "plugin"
        private const val META_SUFFIX = "-meta"
        private const val CACHE_KEY_PREFIX_LENGTH = 16
        private val UNSAFE_EXTENSION_CHARS = Regex("[^a-zA-Z0-9]")

        /**
         * Generate a stable, CAS-safe cache key for a URL and optional platform.
         *
         * Keys are filename-safe (no `/`, `\`, `:`).
         */
        internal fun cacheKey(
            url: String,
            platform: String? = null,
        ): String {
            val input = if (platform != null) "$url|$platform" else url
            val hash = CryptoProvider.sha256(input.encodeToByteArray())
            val prefix = hash.take(CACHE_KEY_PREFIX_LENGTH).toByteArray().toHexString()
            return if (platform != null) {
                val safePlatform = platform
                    .replace('/', '-')
                    .replace('\\', '-')
                    .replace(':', '-')
                "$KEY_PREFIX-$prefix-$safePlatform"
            } else {
                "$KEY_PREFIX-$prefix"
            }
        }
    }

    /**
     * Metadata stored alongside each cached plugin binary.
     */
    @Serializable
    data class DownloadMetadata(
        val url: String,
        val checksum: String? = null,
        val extension: String = "jar",
    )

    /**
     * A resolved cache entry pointing to the cached plugin file.
     *
     * [filePath] is a temporary file written from CAS bytes on each [get] call.
     * Call [cleanup] when the entry is no longer needed to delete the temp file.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `Path`.
     */
    data class CacheEntry(
        val filePath: Path,
        val url: String,
        val checksum: String?,
        val timestamp: Long,
    ) {
        /** Deletes the temporary file backing this entry. Safe to call more than once. */
        fun cleanup() {
            suspendCatching { SystemFileSystem.delete(filePath, mustExist = false) }
        }
    }

    /**
     * Look up a cached artifact by URL and optional platform.
     *
     * On a cache hit the bytes are written to a temporary file.
     *
     * @param url The original download URL.
     * @param platform Optional platform key (e.g. `"linux-x64"`) to prevent JVM/native collisions.
     * @return The [CacheEntry] if found and not expired, null otherwise.
     */
    suspend fun get(
        url: String,
        platform: String? = null,
    ): CacheEntry? {
        val key = cacheKey(url, platform)

        val bytes = withContext(Dispatchers.IO) { casCache.get(key) } ?: return null
        val indexEntry = casCache.getIndex(key) ?: return null
        val meta = loadMeta(key) ?: run {
            logger.debug("DownloadCache: missing metadata for key $key")
            return null
        }

        if (meta.url != url) return null

        val tempFile = newTempFile(meta.extension)
        try {
            withContext(Dispatchers.IO) { tempFile.writeBytes(bytes) }
        } catch (e: IOException) {
            suspendCatching { SystemFileSystem.delete(tempFile, mustExist = false) }
            logger.debug("DownloadCache: failed to write temp file for $url: ${e.message}")
            return null
        }

        return CacheEntry(
            filePath = tempFile,
            url = meta.url,
            checksum = meta.checksum,
            timestamp = indexEntry.createdAt,
        )
    }

    /**
     * Store a downloaded plugin binary in the cache.
     *
     * The file at [filePath] is read and stored as a CAS blob. A companion metadata
     * entry is stored under the sidecar key.
     *
     * @param url The original download URL.
     * @param filePath Path to the downloaded plugin file (read into cache; not moved).
     * @param checksum Optional checksum string.
     * @param extension Plugin file extension. Defaults to the extension inferred from [url].
     * @param platform Optional platform key to disambiguate JVM vs native artifacts.
     * @return The [CacheEntry] for the stored file.
     */
    suspend fun put(
        url: String,
        filePath: Path,
        checksum: String? = null,
        extension: String = url
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "jar")
            .lowercase()
            .ifBlank { "jar" },
        platform: String? = null,
    ): CacheEntry {
        val bytes = withContext(Dispatchers.IO) { filePath.readBytes() }
        val key = cacheKey(url, platform)
        val meta = DownloadMetadata(url = url, checksum = checksum, extension = extension)

        withContext(Dispatchers.IO) {
            casCache.put(key, bytes, ttlMillis)
            val metaJson = KarandashJson.Default.encodeToString(DownloadMetadata.serializer(), meta)
            casCache.put(metaKey(key), metaJson.encodeToByteArray(), ttlMillis)
        }

        val timestamp = casCache.getIndex(key)?.createdAt ?: 0L
        return CacheEntry(
            filePath = filePath,
            url = url,
            checksum = checksum,
            timestamp = timestamp,
        )
    }

    /**
     * Invalidate (remove) a specific cached entry by URL and optional platform.
     *
     * @return true if the content entry existed before removal.
     */
    fun invalidate(
        url: String,
        platform: String? = null,
    ): Boolean {
        val key = cacheKey(url, platform)
        val existed = casCache.getIndex(key) != null
        casCache.invalidate(key)
        casCache.invalidate(metaKey(key))
        return existed
    }

    /**
     * Clear all cached entries belonging to this cache.
     *
     * @return Number of content entries removed.
     */
    fun clearAll(): Int {
        val keys = casCache
            .keys()
            .filter { it.startsWith("$KEY_PREFIX-") && !it.endsWith(META_SUFFIX) }
        for (k in keys) {
            casCache.invalidate(k)
            casCache.invalidate(metaKey(k))
        }
        return keys.size
    }

    /**
     * List all non-expired cached entries.
     *
     * @return List of [CacheEntry] objects.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun entries(): List<CacheEntry> {
        val keys = casCache
            .keys()
            .filter { it.startsWith("$KEY_PREFIX-") && !it.endsWith(META_SUFFIX) }
        val result = mutableListOf<CacheEntry>()

        for (key in keys) {
            try {
                val bytes = withContext(Dispatchers.IO) { casCache.get(key) } ?: continue
                val indexEntry = casCache.getIndex(key) ?: continue
                val meta = loadMeta(key) ?: continue
                val tempFile = newTempFile(meta.extension)
                withContext(Dispatchers.IO) { tempFile.writeBytes(bytes) }
                result.add(
                    CacheEntry(
                        filePath = tempFile,
                        url = meta.url,
                        checksum = meta.checksum,
                        timestamp = indexEntry.createdAt,
                    )
                )
            } catch (e: Exception) {
                // boundary: CAS read of an untrusted cache entry
                e.rethrowCancellation()
                logger.debug("DownloadCache: skipping corrupt entry $key: ${e.message}")
            }
        }
        return result
    }

    // ---- private helpers ----

    private fun metaKey(contentKey: String): String = "$contentKey$META_SUFFIX"

    @Suppress("TooGenericExceptionCaught")
    private fun loadMeta(key: String): DownloadMetadata? {
        return try {
            val bytes = casCache.get(metaKey(key)) ?: return null
            KarandashJson.Default.decodeFromString(DownloadMetadata.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            // boundary: CAS read of an untrusted cache entry
            e.rethrowCancellation()
            null
        }
    }

    private fun newTempFile(extension: String): Path {
        val safeExt = extension.replace(UNSAFE_EXTENSION_CHARS, "").ifBlank { "jar" }
        val hex = Random
            .nextLong()
            .toULong()
            .toString(16)
            .padStart(16, '0')
        return Path(SystemTemporaryDirectory, ".karandash-dl-$hex.$safeExt")
    }
}
