package io.karandash.manifest

import io.karandash.config.MergeStrategy
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * How `{ file: "..." }` references in a remote config are resolved.
 */
enum class RemoteFileResolution {
    /**
     * Default: resolve file references locally only.
     * Fails with an error if the local file is not found - no remote fallback.
     */
    LOCAL,

    /**
     * Try local first; if not found, try remote (with a warning).
     * Fails with an error if both local and remote fail.
     */
    FALLBACK_REMOTE,

    /**
     * Try to fetch the file from the remote server; if that fails,
     * fall back to resolving against the local working directory and emit a warning.
     */
    FALLBACK_LOCAL,

    /**
     * Strict mode: file references must resolve from the remote server.
     * Fails with an error if the remote fetch fails - no local fallback.
     */
    STRICT;

    companion object {
        /**
         * Parse from a config string (case-insensitive, supports kebab-case).
         *
         * @return The matching [RemoteFileResolution], or null if [value] does not match any entry.
         */
        fun fromString(value: String): RemoteFileResolution? =
            when (value.lowercase().replace('_', '-')) {
                "local" -> LOCAL
                "fallback-remote", "fallbackremote" -> FALLBACK_REMOTE
                "fallback-local", "fallbacklocal", "fallback" -> FALLBACK_LOCAL
                "strict" -> STRICT
                else -> null
            }
    }
}

sealed class ExtendsEntry {
    abstract val condition: String?
    abstract val mergeStrategy: MergeStrategyMap?

    /** How the whole layer merges onto its parents: a string `merge-strategy`; path modes apply under [MergeStrategy.DEEP]. */
    abstract val topLevelMerge: MergeStrategy

    /**
     * Simple URL/path reference (wraps existing ConfigSource)
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `ConfigSource`.
     */
    data class UrlEntry(
        val source: ConfigSource,
        override val condition: String? = null,
        val cache: CachePolicy? = null,
        override val mergeStrategy: MergeStrategyMap? = null,
        val fileResolution: RemoteFileResolution = RemoteFileResolution.LOCAL,
        override val topLevelMerge: MergeStrategy = MergeStrategy.DEEP,
    ) : ExtendsEntry()

    /** Glob pattern that expands to multiple file paths (local only) */
    @Serializable
    data class GlobEntry(
        val pattern: String,
        override val condition: String? = null,
        val cache: CachePolicy? = null,
        override val mergeStrategy: MergeStrategyMap? = null,
        override val topLevelMerge: MergeStrategy = MergeStrategy.DEEP,
    ) : ExtendsEntry()

    /** Manifest reference like @standard */
    @Serializable
    data class ManifestRef(
        val name: String,
        override val condition: String? = null,
        override val mergeStrategy: MergeStrategyMap? = null,
        override val topLevelMerge: MergeStrategy = MergeStrategy.DEEP,
    ) : ExtendsEntry()
}

/**
 * Cache policy for remote config extends entries.
 *
 * @property ttl Cache lifetime. Use `"0s"` to disable caching, `"never"` to cache forever.
 */
@Serializable
data class CachePolicy(
    val ttl: KarandashDuration = DEFAULT_CACHE_TTL,
) {
    companion object {
        val DEFAULT_CACHE_TTL: KarandashDuration = KarandashDuration.ofSeconds(3600)
    }
}
