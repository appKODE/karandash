package io.karandash.cache.cas

import io.karandash.cache.CacheManager
import io.karandash.cache.remote.RemoteCacheLayer
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.platform.absolutePathOrSelf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerializationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Unified cache combining [CasBlobStore] (immutable content) and
 * [CacheIndexStore] (mutable pointers with TTL/ETag).
 *
 * Provides a `getOrCompute` pattern: check index → read blob → on miss, compute + store.
 *
 * @param baseDir Root cache directory (e.g. `~/.karandash/cache/v1`).
 * @param namespace Consumer-specific index namespace (e.g. `"provider"`, `"extends"`, `"ai"`).
 * @param fs File system abstraction.
 * @param logger Logger for debug messages.
 * @param remote Remote layer that blobs are pushed to on [put] and pulled from by [fetch], or null
 *   when this namespace is not shared remotely. Read lazily, since the layer is wired after creation.
 * @param shareIndex Whether index entries are shared through [remote] too, so a machine without the local
 *   index (a fresh CI runner) still finds the content. [put] pushes each entry under [indexDigest]; [fetch] pulls
 *   it on a local index miss. A local index always wins and the remote is then not asked. Concurrent writers of
 *   the same key race: the last push wins.
 */
class CasCache(
    baseDir: Path,
    private val namespace: String,
    fs: FileSystem = SystemFileSystem,
    private val logger: Logger = NoOpLogger,
    private val remote: () -> RemoteCacheLayer? = { null },
    private val shareIndex: Boolean = false,
) {
    private val blobStore = CasBlobStore(Path(baseDir, CacheManager.CAS_BLOBS_DIR), fs, logger)
    private val indexStore = CacheIndexStore(Path(baseDir, CacheManager.INDEX_DIR), fs, logger)
    private val source = MutableStateFlow(CacheSource.NONE)
    private val fetched = MutableStateFlow(false)

    /** Where [fetch] found content this run: [CacheSource.REMOTE] if any index came from the remote, else local or none. */
    fun indexSource(): CacheSource = source.value

    /** True when [fetch] was called this run and found nothing in either layer: no prior state, a first run. */
    fun foundNothing(): Boolean = fetched.value && source.value == CacheSource.NONE

    /**
     * Get cached content by key, or compute and store it.
     *
     * @param key Cache key (hashed or human-readable - caller decides).
     * @param ttlMs Time-to-live in milliseconds.
     * @param compute Function to produce fresh content on cache miss.
     * @return Cached or freshly computed content.
     */
    @OptIn(ExperimentalTime::class)
    fun getOrCompute(
        key: String,
        ttlMs: Long,
        compute: () -> ByteArray,
    ): ByteArray {
        val cached = get(key)
        if (cached != null) return cached

        val content = compute()
        put(key, content, ttlMs)
        return content
    }

    /**
     * Read cached content by key. Returns null on miss, expiry, or blob not found; a missing blob also removes the
     * index entry pointing at it unless a remote layer may still hold the blob.
     */
    @OptIn(ExperimentalTime::class)
    fun get(key: String): ByteArray? {
        val entry = indexStore.read(namespace, key) ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        if (entry.isExpired(now)) {
            logger.debug("CAS cache expired: $namespace/$key")
            return null
        }
        // a live entry whose blob is gone (GC'd under a concurrent run) is dropped, so the next put repopulates it;
        // with a remote layer it stays, since [fetch] can still pull the blob
        return blobStore.get(entry.casDigest()) ?: run {
            if (remote() != null) return null
            indexStore.delete(namespace, key)
            logger.debug("CAS cache dangling index entry removed: $namespace/$key")
            null
        }
    }

    /**
     * Like [get], but a live index entry whose blob is missing locally is pulled from the [remote]
     * layer (which stores it in the local blob store). With [shareIndex], a missing local index entry is pulled
     * from the remote too and written locally once its blob resolves.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun fetch(key: String): ByteArray? {
        fetched.value = true
        val localEntry = indexStore.read(namespace, key)
        val entry = localEntry ?: pullIndex(key) ?: return null
        if (entry.isExpired(Clock.System.now().toEpochMilliseconds())) return null
        val digest = entry.casDigest()
        val content = blobStore.get(digest) ?: remote()?.get(digest) ?: return null
        if (localEntry == null) {
            indexStore.write(namespace, key, entry)
            logger.debug("Cache index restored from remote: $namespace/$key")
        }
        val found = if (localEntry == null) CacheSource.REMOTE else CacheSource.LOCAL
        source.update { maxOf(it, found) }
        return content
    }

    /**
     * Read the index entry (without reading the blob). Useful for ETag checks.
     */
    fun getIndex(key: String): CacheIndexEntry? = indexStore.read(namespace, key)

    /**
     * Store content as a CAS blob and write an index entry.
     *
     * @param key Cache key.
     * @param content Content bytes to store.
     * @param ttlMs Time-to-live in milliseconds.
     * @param etag Optional HTTP ETag for conditional revalidation.
     */
    @OptIn(ExperimentalTime::class)
    fun put(
        key: String,
        content: ByteArray,
        ttlMs: Long,
        etag: String? = null,
    ) {
        val digest = remote()?.put(content) ?: blobStore.put(content)
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = CacheIndexEntry(digest = digest.hex, etag = etag, expiresAt = now + ttlMs, createdAt = now)
        indexStore.write(namespace, key, entry)
        if (shareIndex) {
            remote()?.push(indexDigest(key), KarandashJson.Default.encodeToString(CacheIndexEntry.serializer(), entry).encodeToByteArray())
        }
        logger.debug(
            "Cache put: ns=$namespace key=$key size=${content.size}" +
                " blob=${blobStore.blobPath(digest)} index=${indexStore.indexPath(namespace, key).absolutePathOrSelf()}",
        )
    }

    /**
     * Refresh the TTL of an existing index entry (e.g. after HTTP 304).
     */
    @OptIn(ExperimentalTime::class)
    fun refreshTtl(
        key: String,
        ttlMs: Long
    ) {
        val entry = indexStore.read(namespace, key) ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        indexStore.write(namespace, key, entry.copy(expiresAt = now + ttlMs))
    }

    /**
     * Remove the index entry. CAS blob is left for GC (may be referenced by other keys).
     */
    fun invalidate(key: String) {
        indexStore.delete(namespace, key)
    }

    /** List all cached keys in this namespace. */
    fun keys(): List<String> = indexStore.listKeys(namespace)

    /** Synthetic remote name of the index entry for [key]: the digest of `"namespace/key"`, not of the entry. */
    fun indexDigest(key: String): CasDigest = CasDigest.of("$namespace/$key".encodeToByteArray())

    private suspend fun pullIndex(key: String): CacheIndexEntry? {
        if (!shareIndex) return null
        val bytes = remote()?.pullIndex(indexDigest(key)) ?: return null
        return try {
            KarandashJson.Default.decodeFromString(CacheIndexEntry.serializer(), bytes.decodeToString()).also { it.casDigest() }
        } catch (e: SerializationException) {
            logger.warn("Remote cache index for $namespace/$key unreadable: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            logger.warn("Remote cache index for $namespace/$key unreadable: ${e.message}")
            null
        }
    }

    /** Direct access to the blob store (for GC). */
    internal fun blobStore(): CasBlobStore = blobStore

    /** Direct access to the index store (for GC). */
    internal fun indexStore(): CacheIndexStore = indexStore
}

/** Which layer served a [CasCache.fetch]; ordered so the larger one wins when a run sees several. */
enum class CacheSource {
    NONE,
    LOCAL,
    REMOTE,
    ;

    /** Lowercase name, as written to `cache.source` in the diagnostic report. */
    val id: String get() = name.lowercase()
}
