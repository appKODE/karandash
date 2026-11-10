package io.karandash.utils

import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.GlobCacheConfig
import io.karandash.cache.TieredCache
import io.karandash.config.KarandashLimits
import kotlinx.atomicfu.atomic

/**
 * Matches file paths against glob patterns.
 *
 * Supported patterns:
 * - `*` matches any characters except `/`
 * - `**` matches any characters including `/`
 * - double-star-slash at start matches zero or more path segments
 * - slash-double-star at end matches zero or more path segments
 * - `?` matches a single character except `/`
 *
 * Injectable via constructor. For convenience, use [GlobMatcher.matches] and
 * [GlobMatcher.codeownersMatches] companion methods which delegate to [Default].
 *
 * @param cache The regex cache for compiled glob patterns.
 */
class GlobMatcher(
    private val cache: TieredCache<String, Regex>,
) {
    // Own map: a plain glob literally named like a CODEOWNERS key must not hit a CODEOWNERS regex.
    private val codeownersCache = TieredCache<String, Regex>(
        name = CODEOWNERS_CACHE_NAME,
        maxEntries = KarandashLimits.MAX_CACHE_SIZE,
        tier = CacheTier.MEMORY_ONLY,
    )

    /**
     * Check if a file path matches a glob pattern.
     */
    fun matchPath(
        path: String,
        pattern: String,
    ): Boolean {
        val regex = cache.getOrCompute(pattern) { globToRegex(pattern) }
        return regex.matches(path)
    }

    /**
     * Matches [value] against a label/text [pattern]: a glob when it has `*` or `?` (both cross `/`, unlike
     * [matchPath]), else an exact comparison. Compiled globs share this matcher's cache, so `cache.glob` sizes them.
     */
    fun matchWildcard(
        value: String,
        pattern: String,
        ignoreCase: Boolean = false,
    ): Boolean {
        if ('*' !in pattern && '?' !in pattern) return value.equals(pattern, ignoreCase)
        // NUL never appears in a config glob, so a wildcard key cannot collide with a path pattern
        val key = "\u0000${if (ignoreCase) 'i' else 'c'}$pattern"
        val regex = cache.getOrCompute(key) { wildcardToRegex(pattern, ignoreCase) }
        return regex.matches(value)
    }

    /**
     * Check if a file path matches a CODEOWNERS-style pattern.
     *
     * Handles CODEOWNERS-specific preprocessing before delegating to [globToRegex]:
     * - Leading `/` anchors the pattern to the repository root
     * - Trailing `/` expands to match everything under the directory
     * - A pattern matching a directory also matches every path under it
     */
    fun matchCodeowners(
        path: String,
        pattern: String,
    ): Boolean {
        val normalizedPath = path.removePrefix("/")
        var normalized = pattern.trim()
        val anchoredToRoot = normalized.startsWith("/")
        if (anchoredToRoot) {
            normalized = normalized.removePrefix("/")
        }
        if (normalized.endsWith("/")) {
            normalized += "**"
        }

        val regexPattern = if (anchoredToRoot) normalized else "**/$normalized"
        val regex = codeownersCache.getOrCompute(pattern) { globToRegex(regexPattern) }
        // A pattern naming a directory (`docs`, `src/*`) owns everything under it, as in gitignore.
        return regex.matches(normalizedPath) ||
            normalizedPath.indices.any { normalizedPath[it] == '/' && regex.matches(normalizedPath.substring(0, it)) }
    }

    companion object {
        /** Cache name for use with [CacheManager]. */
        const val CACHE_NAME = GlobCacheConfig.CACHE_NAME

        private const val CODEOWNERS_CACHE_NAME = "glob-codeowners"

        private val current = atomic(
            GlobMatcher(
                cache = TieredCache(
                    name = CACHE_NAME,
                    maxEntries = KarandashLimits.MAX_CACHE_SIZE,
                    tier = CacheTier.MEMORY_ONLY,
                ),
            ),
        )

        /**
         * Process-wide instance used by static [matches] and [codeownersMatches] helpers. Unconfigured it holds
         * [KarandashLimits.MAX_CACHE_SIZE] patterns; [useManaged] resizes it from the run's `cache:` block.
         */
        val Default: GlobMatcher get() = current.value

        /**
         * Backs [Default] with [cacheManager]'s `glob-patterns` cache, so `cache.glob.maxEntries` (or
         * `cache.memory.overrides.glob-patterns`) sizes it. Called once per run when the cache manager is built.
         */
        fun useManaged(cacheManager: CacheManager) {
            current.value = createManaged(cacheManager)
        }

        /**
         * Creates a [GlobMatcher] backed by a [CacheManager]-managed cache.
         *
         * Use this in the validation pipeline so the glob cache participates in
         * central stats, invalidation, and lifecycle management.
         */
        fun createManaged(cacheManager: CacheManager): GlobMatcher {
            val cache = cacheManager.getOrCreate<String, Regex>(
                CACHE_NAME,
                CacheSpec(maxEntries = KarandashLimits.MAX_CACHE_SIZE),
            )
            return GlobMatcher(cache)
        }

        /** Static match helper delegating to [Default]. */
        fun matches(
            path: String,
            pattern: String,
        ): Boolean = Default.matchPath(path, pattern)

        /** Static [matchWildcard] helper delegating to [Default]. */
        fun wildcardMatches(
            value: String,
            pattern: String,
            ignoreCase: Boolean = false,
        ): Boolean = Default.matchWildcard(value, pattern, ignoreCase)

        /** Static CODEOWNERS match helper delegating to [Default]. */
        fun codeownersMatches(
            path: String,
            pattern: String,
        ): Boolean = Default.matchCodeowners(path, pattern)
    }
}

/** Path-aware glob → regex via [GlobTranslator.Mode.PATH]. */
private fun globToRegex(pattern: String): Regex = Regex(GlobTranslator.toRegexSource(pattern, GlobTranslator.Mode.PATH))

/** Label/text glob → regex via [GlobTranslator.Mode.WILDCARD]. */
private fun wildcardToRegex(
    pattern: String,
    ignoreCase: Boolean,
): Regex =
    Regex(
        GlobTranslator.toRegexSource(pattern, GlobTranslator.Mode.WILDCARD),
        if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet(),
    )
