package io.karandash.cache

import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.duration.KarandashDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User-facing cache configuration parsed from the `cache:` config block.
 *
 * ```yaml
 * cache:
 *   enabled: true
 *   dir: "~/.karandash/cache"
 *   responses:
 *     enabled: true
 *     ttl: "60s"
 *   downloads:
 *     enabled: true
 *     ttl: "1h"
 *   state:
 *     enabled: true
 *     ttl: "7d"
 *   memory:
 *     max-entries: 512
 *     overrides:
 *       responses:
 *         max-entries: 256
 *       glob-patterns:
 *         max-entries: 1024
 * ```
 */
@Serializable
data class CacheConfig(
    val enabled: Boolean = true,
    val dir: String = DEFAULT_DIR,
    val responses: ResponsesCacheConfig = ResponsesCacheConfig(),
    val downloads: DownloadsCacheConfig = DownloadsCacheConfig(),
    val state: StateCacheConfig = StateCacheConfig(),
    val memory: MemoryCacheConfig = MemoryCacheConfig(),
    /** Optional remote cache backend. Null means local-only caching. */
    val remote: RemoteCacheConfig? = null,
    /** Provider response TTL when `cache.responses.ttl` is unset and there is no `timeouts:` block (`timeouts.cacheTtl`). */
    val defaultTtl: KarandashDuration? = null,
    /**
     * Maximum total on-disk cache size in bytes across the namespaces a run used.
     *
     * When set, the end-of-run GC ([io.karandash.cache.cas.CacheGarbageCollector.evictOverSize]) evicts
     * the oldest entries after expiry cleanup until their blobs fit. Null means unbounded (default).
     */
    val maxCacheSize: Long? = null,
    /** Glob pattern cache sizing. */
    val glob: GlobCacheConfig = GlobCacheConfig(),
    /** Compiled regex cache sizing. */
    val regex: RegexCacheConfig = RegexCacheConfig(),
    /** Named pattern resolver cache sizing. */
    val patterns: PatternCacheConfig = PatternCacheConfig(),
) {
    companion object {
        const val DEFAULT_DIR = "~/.karandash/cache"
    }
}

/**
 * Configuration for the HTTP response cache subsystem.
 *
 * Controls caching of provider API responses (GitLab, GitHub, etc.) with
 * ETag-based conditional requests and TTL expiry.
 */
@Serializable
data class ResponsesCacheConfig(
    val enabled: Boolean = true,
    /** Response TTL; null = `timeouts.cacheTtl`, else [DEFAULT_TTL]. */
    val ttl: KarandashDuration? = null,
    /** Also keep responses in the on-disk cache so a later run within [ttl] reuses them; off: memory only, per run. */
    val persist: Boolean = false,
) {
    companion object {
        val DEFAULT_TTL: KarandashDuration = KarandashDuration.ofSeconds(60)
    }
}

/**
 * Configuration for the downloads cache subsystem: caching of remote `extends:` configs.
 * Inherits through `extends:`: each layer's parents are fetched under the value merged from the root down to that
 * layer (the closer layer wins); an entry's own `cache.ttl` wins over [ttl].
 */
@Serializable
data class DownloadsCacheConfig(
    val enabled: Boolean = true,
    val ttl: KarandashDuration = DEFAULT_TTL,
) {
    companion object {
        val DEFAULT_TTL: KarandashDuration = KarandashDuration.ofSeconds(3_600)
    }
}

/**
 * Configuration for the state cache subsystem.
 *
 * Controls caching of per-MR validation state (staleness gate findings, AI state).
 */
@Serializable
data class StateCacheConfig(
    val enabled: Boolean = true,
    val ttl: KarandashDuration = DEFAULT_TTL,
) {
    companion object {
        val DEFAULT_TTL: KarandashDuration = KarandashDuration.ofSeconds(604_800)
    }
}

/**
 * Configuration for in-memory cache sizing.
 *
 * [maxEntries] is the global default. Per-cache [overrides] take precedence
 * when set for a given cache name (e.g. `"responses"`, `"glob-patterns"`).
 *
 * ```yaml
 * memory:
 *   max-entries: 512
 *   overrides:
 *     responses:
 *       max-entries: 256
 *     glob-patterns:
 *       max-entries: 1024
 * ```
 */
@Serializable
data class MemoryCacheConfig(
    @SerialName("maxEntries") val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val overrides: Map<String, MemoryOverride> = emptyMap(),
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
    }
}

/**
 * Per-subsystem memory cache size override.
 *
 * All fields are nullable - only non-null values override the global default.
 */
@Serializable
data class MemoryOverride(
    @SerialName("maxEntries") val maxEntries: Int? = null,
)

/**
 * Cache sizing for compiled glob patterns in [io.karandash.utils.GlobMatcher].
 */
@Serializable
data class GlobCacheConfig(
    /** Maximum number of compiled glob regex entries. */
    @SerialName("maxEntries") val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 1000

        /** Name of the managed cache this block sizes. */
        const val CACHE_NAME = "glob-patterns"
    }
}

/**
 * Cache sizing for compiled regex patterns in [io.karandash.helpers.SafeRegexCompiler].
 */
@Serializable
data class RegexCacheConfig(
    /** Maximum number of compiled regex entries. */
    @SerialName("maxEntries") val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 1000

        /** Name of the managed cache this block sizes. */
        const val CACHE_NAME = "safe-regex"
    }
}

/**
 * Cache sizing for resolved named patterns in [io.karandash.patterns.PatternResolver].
 */
@Serializable
data class PatternCacheConfig(
    /** Maximum number of cached pattern resolution results. */
    @SerialName("maxEntries") val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 512

        /** Name of the managed cache this block sizes. */
        const val CACHE_NAME = "pattern-resolver"
    }
}
