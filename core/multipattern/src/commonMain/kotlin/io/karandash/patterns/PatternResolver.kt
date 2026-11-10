package io.karandash.patterns

import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.PatternCacheConfig
import io.karandash.cache.TieredCache
import io.karandash.helpers.PatternPrefixes
import io.karandash.helpers.SafeRegexCompiler

/**
 * Resolves pattern strings from all 5 tiers into [PatternCompilationResult].
 *
 * Tier routing:
 * - `@name`      → [PatternRegistry] lookup + recursive expansion
 * - `pomsky:`    → [PomskyCompiler] (if provided; error otherwise)
 * - `glob:`      → [TextGlobCompiler]
 * - `regex:`     → direct regex compile via [SafeRegexCompiler]
 * - bare string  → raw regex for backward compatibility
 *
 * Results are cached in a bounded LRU cache keyed on the raw pattern string.
 *
 * @param registry Named pattern registry for `@`-prefixed lookups.
 * @param pomskyCompiler Optional Pomsky backend; absent → `pomsky:` patterns return [PatternCompilationResult.Error].
 * @param globCompiler Text glob compiler (defaults to a new [TextGlobCompiler] instance).
 * @param regexCompiler Regex compiler for bare/regex patterns (defaults to [SafeRegexCompiler.Default]).
 * @param cache Optional shared cache; a standalone memory-only cache is created if absent.
 */
class PatternResolver(
    private val registry: PatternRegistry,
    private val pomskyCompiler: PomskyCompiler? = null,
    private val globCompiler: TextGlobCompiler = TextGlobCompiler(),
    private val regexCompiler: SafeRegexCompiler = SafeRegexCompiler.Default,
    cache: TieredCache<String, PatternCompilationResult>? = null,
) {
    private val cache: TieredCache<String, PatternCompilationResult> = cache ?: TieredCache(
        name = CACHE_NAME,
        maxEntries = MAX_CACHE_SIZE,
        tier = CacheTier.MEMORY_ONLY,
    )

    /**
     * Resolves [pattern] to a [PatternCompilationResult].
     *
     * The raw [pattern] string (including any prefix) is used as the cache key.
     */
    fun resolve(pattern: String): PatternCompilationResult = this.cache.getOrCompute(pattern) { compileUncached(pattern) }

    /**
     * Recursively resolves `@name` references within a pattern expansion string,
     * returning the fully-expanded regex string.
     *
     * Unlike [resolve], this operates on a pre-expanded string that may contain
     * embedded `@name` tokens and returns the raw regex text (not a compiled result).
     *
     * @param pattern The pattern string potentially containing `@name` references.
     * @param maxDepth Maximum recursion depth to prevent infinite loops.
     * @return The fully-resolved regex string with all `@name` tokens expanded.
     * @throws IllegalArgumentException on circular references or depth overflow.
     */
    fun resolveNested(
        pattern: String,
        maxDepth: Int = NamedPatternExpander.maxDepth,
    ): String =
        NamedPatternExpander.resolveInnerReferences(
            registry = registry,
            expanded = pattern,
            visited = emptySet(),
            depth = (NamedPatternExpander.maxDepth - maxDepth).coerceAtLeast(0),
        )

    /** The `@name` form of every registered pattern that compiles without parameters (the bridged guests' table). */
    fun namedPatterns(): List<String> = registry.all().map { "@${it.name}" }.filter { resolve(it) is PatternCompilationResult.Success }

    private fun compileUncached(pattern: String): PatternCompilationResult =
        when (detectPatternType(pattern)) {
            PatternType.NAMED -> resolveNamed(pattern)
            PatternType.POMSKY -> resolvePomsky(pattern)
            PatternType.GLOB -> resolveGlob(pattern)
            PatternType.REGEX -> resolveRegex(pattern)
            PatternType.BARE -> resolveBare(pattern)
        }

    private fun resolveNamed(pattern: String): PatternCompilationResult {
        val expanded = try {
            NamedPatternExpander.expand(registry, pattern)
        } catch (e: IllegalArgumentException) {
            return PatternCompilationResult.Error(
                message = e.message ?: "Named pattern expansion failed for $pattern",
                originalPattern = pattern,
            )
        }
        // Route the expanded string through the full tier router so that named patterns
        // can expand to glob:, regex:, pomsky:, or bare regex strings.
        return when (val inner = compileUncached(expanded)) {
            is PatternCompilationResult.Success -> {
                inner.copy(originalPattern = pattern, type = PatternType.NAMED)
            }

            is PatternCompilationResult.Error -> {
                inner.copy(originalPattern = pattern)
            }
        }
    }

    private fun resolvePomsky(pattern: String): PatternCompilationResult {
        val compiler = pomskyCompiler
            ?: return PatternCompilationResult.Error(
                message = "Pomsky compiler is not available in this environment",
                originalPattern = pattern,
            )
        val expression = pattern.removePrefix(PatternPrefixes.POMSKY).trim()
        val result = compiler.compile(expression)
        if (result.regex == null) {
            return PatternCompilationResult.Error(
                message = "Pomsky compilation failed: ${result.error ?: "unknown error"}",
                originalPattern = pattern,
            )
        }
        return compileRawRegex(result.regex, pattern, PatternType.POMSKY)
    }

    private fun resolveGlob(pattern: String): PatternCompilationResult {
        val glob = pattern.removePrefix(PatternPrefixes.GLOB)
        return globCompiler.compile(glob).let { result ->
            when (result) {
                is PatternCompilationResult.Success -> result.copy(originalPattern = pattern)
                is PatternCompilationResult.Error -> result.copy(originalPattern = pattern)
            }
        }
    }

    private fun resolveRegex(pattern: String): PatternCompilationResult {
        val regexStr = pattern.removePrefix(PatternPrefixes.REGEX)
        return compileRawRegex(regexStr, pattern, PatternType.REGEX)
    }

    private fun resolveBare(pattern: String): PatternCompilationResult = compileRawRegex(pattern, pattern, PatternType.BARE)

    private fun compileRawRegex(
        regexStr: String,
        originalPattern: String,
        type: PatternType,
    ): PatternCompilationResult =
        when (val result = regexCompiler.compile(regexStr)) {
            is SafeRegexCompiler.CompileResult.Success -> {
                PatternCompilationResult.Success(
                    regex = result.regex,
                    originalPattern = originalPattern,
                    type = type,
                )
            }

            is SafeRegexCompiler.CompileResult.TooLong -> {
                PatternCompilationResult.Error(
                    message = "Regex pattern too long (${result.length} > ${result.maxLength} chars)",
                    originalPattern = originalPattern,
                )
            }

            is SafeRegexCompiler.CompileResult.NestedQuantifiers -> {
                PatternCompilationResult.Error(
                    message = "Regex has nested quantifiers (depth ${result.depth}) — risk of catastrophic backtracking",
                    originalPattern = originalPattern,
                )
            }

            is SafeRegexCompiler.CompileResult.InvalidPattern -> {
                PatternCompilationResult.Error(
                    message = "Invalid regex pattern: ${result.message}",
                    originalPattern = originalPattern,
                )
            }
        }

    companion object {
        /** Cache name for use with [io.karandash.cache.CacheManager]. */
        const val CACHE_NAME = PatternCacheConfig.CACHE_NAME
        private const val MAX_CACHE_SIZE = 512

        /**
         * Creates a [PatternResolver] backed by [CacheManager]-managed caches.
         *
         * Both the pattern result cache and the underlying [SafeRegexCompiler]
         * participate in central stats, invalidation, and lifecycle management.
         */
        fun createManaged(
            registry: PatternRegistry,
            cacheManager: CacheManager,
            pomskyCompiler: PomskyCompiler? = null,
        ): PatternResolver {
            val cache = cacheManager.getOrCreate<String, PatternCompilationResult>(
                CACHE_NAME,
                CacheSpec(maxEntries = MAX_CACHE_SIZE),
            )
            val regexCompiler = SafeRegexCompiler.createManaged(cacheManager)
            return PatternResolver(
                registry = registry,
                pomskyCompiler = pomskyCompiler,
                globCompiler = TextGlobCompiler(regexCompiler),
                regexCompiler = regexCompiler,
                cache = cache,
            )
        }
    }
}
