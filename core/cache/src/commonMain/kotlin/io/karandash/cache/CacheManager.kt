package io.karandash.cache

import io.karandash.api.cache.CacheProvider
import io.karandash.api.cache.KarandashCache
import io.karandash.cache.cas.CacheGarbageCollector
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasCache
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.cas.GcResult
import io.karandash.cache.remote.RemoteCacheBackend
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cache.remote.RemoteCacheLayer
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.writeText
import io.karandash.utils.CacheStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Duration

/**
 * Central registry and factory for all application caches.
 *
 * Provides a single entry point for cache creation, stats aggregation,
 * bulk invalidation, and lifecycle management. All caches created through
 * this manager are tracked and can be inspected or cleared centrally.
 *
 * Memory max-entries for named caches are resolved via [memoryMaxEntries]:
 * per-cache overrides in `cache.memory.{name}` take precedence over the
 * global `cache.memory.max-entries` default.
 *
 * Thread-safe via [MutableStateFlow] with immutable map snapshots.
 *
 * @param config User cache configuration (from `cache:` config block).
 * @param cacheDir Resolved base directory for CAS-backed caches.
 * @param logger Logger for file-tier cache decisions (hit/miss, remote pull/push).
 * @param readFiles False (`--no-cache`) makes file-tier caches skip reads but keep writing; the state store
 *   ([getCasCache]) is not affected.
 */
class CacheManager(
    private val config: CacheConfig = CacheConfig(),
    private val cacheDir: Path,
    private val logger: Logger = NoOpLogger,
    val readFiles: Boolean = true,
) : CacheProvider {
    private val state = MutableStateFlow<Map<String, TieredCache<*, *>>>(emptyMap())
    private val casCaches = MutableStateFlow<Map<String, CasCache>>(emptyMap())

    /**
     * Shared CAS blob store for the base cache directory.
     *
     * Used by [buildRemoteLayer] to provide a single blob store for remote sync operations.
     */
    val sharedBlobStore: CasBlobStore = CasBlobStore(Path(cacheDir, CAS_BLOBS_DIR))

    private val _remoteLayer = MutableStateFlow<RemoteCacheLayer?>(null)

    /**
     * Active remote cache layer, or null if no remote backend is configured.
     *
     * @lifecycle per-run - set once during pipeline initialization, before first use.
     */
    val remoteLayer: RemoteCacheLayer? get() = _remoteLayer.value

    /** Whether caching is globally enabled. */
    override val enabled: Boolean get() = config.enabled

    override fun getStringCache(
        name: String,
        maxEntries: Int
    ): KarandashCache {
        val tiered = getOrCreate<String, String>(
            name,
            CacheSpec(maxEntries = maxEntries, tier = CacheTier.MEMORY_ONLY),
        )
        return KarandashCacheAdapter(tiered)
    }

    /**
     * Like [getStringCache], but entries also persist under the cache dir for [ttl], so a later run finds them.
     * Keys are hashed for the file tier (callers' keys may hold paths); `cache.enabled: false` keeps it memory-only.
     */
    fun getPersistentStringCache(
        name: String,
        maxEntries: Int,
        ttl: Duration,
    ): KarandashCache =
        KarandashCacheAdapter(
            getOrCreate(
                name,
                CacheSpec(maxEntries = maxEntries, ttl = ttl, tier = CacheTier.MEMORY_AND_FILE, serializer = HashedKeyStringSerializer),
            ),
        )

    /** Exposed config for subsystems that need TTL or enabled checks. */
    val cacheConfig: CacheConfig get() = config

    /**
     * Gets or creates a named cache with the given specification.
     *
     * The memory max-entries from [memoryMaxEntries] overrides [spec] defaults
     * when a per-cache or global entry count is configured.
     *
     * @param name Unique cache name.
     * @param spec Default cache specification.
     * @return A managed [TieredCache] instance.
     */
    @Suppress("UNCHECKED_CAST") // boundary: caches are stored type-erased by name; the first spec for a name fixes K and V
    fun <K, V> getOrCreate(
        name: String,
        spec: CacheSpec<K, V>,
    ): TieredCache<K, V> {
        // Fast path: check if already exists
        state.value[name]?.let { return it as TieredCache<K, V> }
        // Slow path: create outside update{} so a lost CAS retry never leaks an orphan instance
        val created = createCache(name, spec)
        return state.updateAndGet { it + (name to (it[name] ?: created)) }[name] as TieredCache<K, V>
    }

    /**
     * Returns the resolved memory max-entries for a named cache.
     *
     * Resolution order:
     * 1. Per-cache override: `cache.memory.{name}.maxEntries`
     * 2. Global default: `cache.memory.maxEntries`
     */
    fun memoryMaxEntries(name: String): Int = config.memory.overrides[name]?.maxEntries ?: config.memory.maxEntries

    /**
     * Gets or creates a [CasCache] for a subsystem namespace.
     *
     * Each namespace gets its own index directory under the base cache dir.
     * Multiple calls with the same namespace return the same instance.
     *
     * @param namespace Subsystem identifier (e.g. `"responses"`, [DOWNLOADS_NAMESPACE], [STATE_NAMESPACE]).
     * @return A [CasCache] instance for the given namespace.
     */
    fun getCasCache(namespace: String): CasCache {
        casCaches.value[namespace]?.let { return it }
        val created = casCache(namespace)
        return checkNotNull(casCaches.updateAndGet { it + (namespace to (it[namespace] ?: created)) }[namespace]) {
            "CasCache not found for $namespace"
        }
    }

    /** Returns stats for all managed caches. */
    fun stats(): Map<String, CacheStats> = state.value.mapValues { (_, cache) -> cache.stats() }

    /** Returns stats for a specific cache, or null if not found. */
    fun stats(name: String): CacheStats? = state.value[name]?.stats()

    /** Returns aggregate stats across all managed caches, plus the remote layer under [REMOTE_STATS] when one is wired. */
    fun aggregateStats(): AggregateCacheStats =
        AggregateCacheStats(perCache = stats() + listOfNotNull(_remoteLayer.value?.let { REMOTE_STATS to it.stats() }))

    /**
     * Invalidates all entries in all managed caches and every registered [getCasCache] namespace.
     *
     * @return The number of CAS index entries removed.
     */
    fun invalidateAll(): Int {
        state.value.values.forEach { it.clear() }
        return casCaches.value.values.sumOf { cas -> cas.keys().onEach(cas::invalidate).size }
    }

    /**
     * Writes [aggregateStats] to [RUN_STATS_FILE] under the cache dir for `cache stats`; a write failure only warns.
     */
    fun saveRunStats() {
        val json = KarandashJson.Default.encodeToString(AggregateCacheStats.serializer(), aggregateStats())
        try {
            createDirectories(cacheDir)
            Path(cacheDir, RUN_STATS_FILE).writeText(json)
        } catch (e: IOException) {
            logger.warn("CacheManager: could not save run stats to $cacheDir: ${e.message}")
        }
    }

    /** Invalidates all entries in a specific named cache. */
    fun invalidate(name: String) {
        state.value[name]?.clear()
    }

    /**
     * Builds and activates a [RemoteCacheLayer] over [sharedBlobStore].
     *
     * Must be called at most once per [CacheManager] instance, before any CAS
     * operations that should benefit from remote caching.
     *
     * @param backend Remote backend to delegate push/pull operations to.
     * @param remoteConfig Remote cache configuration (timeouts, push/pull flags).
     * @param scope Coroutine scope for async push fire-and-forget operations.
     * @param logger Logger for remote cache debug and warning messages.
     * @return The created [RemoteCacheLayer].
     */
    fun buildRemoteLayer(
        backend: RemoteCacheBackend,
        remoteConfig: RemoteCacheConfig,
        scope: CoroutineScope,
        logger: Logger = NoOpLogger,
    ): RemoteCacheLayer {
        val previous = _remoteLayer.value
        if (previous != null) {
            error(
                "buildRemoteLayer() called while a remote layer is already active. " +
                    "Call shutdown() before re-initialising the remote layer."
            )
        }
        val layer = RemoteCacheLayer(
            local = sharedBlobStore,
            remote = backend,
            config = remoteConfig,
            scope = scope,
            logger = logger,
        )
        _remoteLayer.value = layer
        return layer
    }

    /**
     * Runs garbage collection on the CAS cache: removes expired index entries
     * and orphaned blobs not referenced by any index, then (when `cache.maxCacheSize` is set)
     * evicts the oldest entries until the cache fits.
     *
     * @param logger Logger for GC debug messages.
     * @return Combined [GcResult] from expired and orphan collection passes.
     */
    fun runGc(logger: Logger = NoOpLogger): GcResult {
        val gc = CacheGarbageCollector(baseDir = cacheDir, logger = logger)
        val namespaces = casCaches.value.keys.toList()
        val expired = gc.collectExpired(namespaces)
        val orphans = gc.collectOrphans(namespaces)
        val evicted = config.maxCacheSize?.let { gc.evictOverSize(namespaces, it) } ?: GcResult(0, 0, 0L)
        return GcResult(
            expiredIndexesRemoved = expired.expiredIndexesRemoved + evicted.expiredIndexesRemoved,
            orphanBlobsRemoved = expired.orphanBlobsRemoved + orphans.orphanBlobsRemoved + evicted.orphanBlobsRemoved,
            bytesReclaimed = expired.bytesReclaimed + orphans.bytesReclaimed + evicted.bytesReclaimed,
        )
    }

    /** Flushes and releases all managed caches. Call at pipeline end. */
    fun shutdown() {
        state.value = emptyMap()
        casCaches.value = emptyMap()
        _remoteLayer.value = null
    }

    private fun <K, V> createCache(
        name: String,
        spec: CacheSpec<K, V>,
    ): TieredCache<K, V> {
        val perCacheOverride = config.memory.overrides[name]?.maxEntries
        val maxEntries = perCacheOverride ?: dedicatedMaxEntries(name) ?: spec.maxEntries

        // `cache.enabled: false` writes nothing under the cache dir: file-tier caches stay in memory
        val tier = if (config.enabled) spec.tier else CacheTier.MEMORY_ONLY
        val casCache = if (tier == CacheTier.MEMORY_AND_FILE && spec.serializer != null) {
            casCache(name)
        } else {
            null
        }
        logger.debug {
            "CacheManager: created '$name' (maxEntries=$maxEntries, override=${perCacheOverride != null}, " +
                "tier=$tier, file-backed=${casCache != null})"
        }

        return TieredCache(
            name = name,
            maxEntries = maxEntries,
            ttl = spec.ttl,
            tier = tier,
            serializer = spec.serializer,
            casCache = casCache,
            logger = logger,
            readFiles = readFiles,
        )
    }

    /** `cache.glob` / `cache.regex` / `cache.patterns` size their managed caches; `cache.memory.overrides` still wins. */
    private fun dedicatedMaxEntries(name: String): Int? =
        when (name) {
            GlobCacheConfig.CACHE_NAME -> config.glob.maxEntries
            RegexCacheConfig.CACHE_NAME -> config.regex.maxEntries
            PatternCacheConfig.CACHE_NAME -> config.patterns.maxEntries
            else -> null
        }

    /**
     * A [CasCache] for [namespace] that shares blobs through [remoteLayer] when `cache.remote` covers it; the
     * [STATE_NAMESPACE] shares its index entries too, so a fresh runner finds the previous run's state.
     */
    private fun casCache(namespace: String): CasCache =
        CasCache(
            baseDir = cacheDir,
            namespace = namespace,
            logger = logger,
            remote = { _remoteLayer.value?.takeIf { it.covers(namespace) } },
            shareIndex = namespace == STATE_NAMESPACE,
        )

    companion object {
        /** Subdirectory under [cacheDir] holding the shared CAS blob store. */
        const val CAS_BLOBS_DIR = "cas"

        /** Subdirectory under [cacheDir] holding the per-namespace index entries. */
        const val INDEX_DIR = "index"

        /** Namespace of downloaded `extends:` configs and plugin binaries. */
        const val DOWNLOADS_NAMESPACE = "downloads"

        /** Namespace of the `cache.state` store (per-MR module state such as the AI incremental snapshot). */
        const val STATE_NAMESPACE = "state"

        /** [aggregateStats] key of the remote layer's pull hits, misses and deduplicated writes. */
        const val REMOTE_STATS = "remote"

        /** File under the cache dir holding the last run's [aggregateStats], written by [saveRunStats]. */
        const val RUN_STATS_FILE = "last-run-stats.json"

        /** The stats [saveRunStats] wrote under [cacheDir], or null when there are none or they cannot be read. */
        fun readRunStats(
            cacheDir: Path,
            logger: Logger = NoOpLogger,
        ): AggregateCacheStats? =
            try {
                val path = Path(cacheDir, RUN_STATS_FILE)
                if (!SystemFileSystem.exists(path)) {
                    null
                } else {
                    val json = path.readText()
                    KarandashJson.Default.decodeFromString(AggregateCacheStats.serializer(), json)
                }
            } catch (e: IOException) {
                logger.warn("CacheManager: could not read run stats in $cacheDir: ${e.message}")
                null
            } catch (e: SerializationException) {
                logger.warn("CacheManager: unreadable run stats in $cacheDir: ${e.message}")
                null
            }

        /**
         * Creates a [CacheManager] with defaults and no file persistence.
         * Used when no `cache:` block is present in config.
         */
        fun withDefaults(cacheDir: Path): CacheManager =
            CacheManager(
                config = CacheConfig(),
                cacheDir = cacheDir,
            )

        /** Default base directory for cache storage. */
        const val DEFAULT_BASE_DIR = "~/.karandash/cache"

        /**
         * Creates a [CacheManager] with default config, resolving the base dir
         * from the user's home directory. Suitable for standalone CLI commands
         * that run outside the main validation pipeline.
         */
        fun defaultInstance(): CacheManager {
            val baseDir = Path(
                io.karandash.platform.getUserHomeDirectoryString(),
                ".karandash/cache",
            )
            return CacheManager(config = CacheConfig(), cacheDir = baseDir)
        }

        /**
         * Creates a disabled [CacheManager] that produces memory-only caches.
         */
        fun disabled(): CacheManager =
            CacheManager(
                config = CacheConfig(enabled = false),
                cacheDir = Path(".karandash/cache"),
            )
    }
}

/** File-tier serializer for string caches: the key is hashed (no path characters reach the index), the value is stored as is. */
private object HashedKeyStringSerializer : CacheSerializer<String, String> {
    override fun serializeKey(key: String): String = CasDigest.of(key.encodeToByteArray()).hex

    override fun serializeValue(value: String): String = value

    override fun deserializeValue(data: String): String = data
}

/**
 * Specification for creating a cache via [CacheManager.getOrCreate].
 *
 * @param maxEntries Maximum entries in the memory tier.
 * @param ttl Optional time-to-live for entries.
 * @param tier Desired storage tier.
 * @param serializer Serializer for file-tier persistence (required for [CacheTier.MEMORY_AND_FILE]).
 */
@Serializable
data class CacheSpec<K, V>(
    val maxEntries: Int,
    val ttl: kotlin.time.Duration? = null,
    val tier: CacheTier = CacheTier.MEMORY_ONLY,
    val serializer: CacheSerializer<K, V>? = null,
)
