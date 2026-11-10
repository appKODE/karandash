package io.karandash.utils

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.config.KarandashLimits
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.isDirectory
import io.karandash.platform.isFile
import io.karandash.platform.listFiles
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Expands glob patterns to file paths with security constraints; [expandBraces] does the `{a,b}` step alone.
 *
 * Security Features:
 * - Limits brace alternatives (`{a,b}`) and matched files to [maxExpansion] (`limits.glob.maxExpansion`)
 * - Restricts directory traversal depth to [maxDirectoryDepth] (`limits.glob.maxDirectoryDepth`)
 * - Blocks `..` path segments, also ones produced by braces (`v1..2.yaml` is a plain name)
 * - Rejects remote URL patterns (local filesystem only)
 *
 * @property baseDir Base directory for glob expansion (default: current working directory)
 * @property maxExpansion Maximum number of brace-expanded patterns and of matched files.
 * @property maxDirectoryDepth Maximum directory depth walked below the search root.
 * @property logger Receives a warning for each directory the walk cannot read.
 */
class GlobExpander(
    private val baseDir: Path = currentWorkingDirectory(),
    private val maxExpansion: Int = MAX_GLOB_EXPANSION,
    private val maxDirectoryDepth: Int = MAX_DIRECTORY_DEPTH,
    private val logger: Logger = NoOpLogger,
) {
    companion object {
        /** Maximum number of files a glob pattern can expand to (same as MAX_EXTENDS_ENTRIES) */
        const val MAX_GLOB_EXPANSION = KarandashLimits.MAX_GLOB_EXPANSION

        /** Maximum directory depth for recursive glob expansion */
        const val MAX_DIRECTORY_DEPTH = KarandashLimits.MAX_DIRECTORY_DEPTH

        private const val WILDCARD_CHARS = "*?["

        /** Default cap on the number of expanded patterns; callers bound it further with `limits.glob.maxExpansion`. */
        const val DEFAULT_MAX_EXPANSION = 1024

        /**
         * Expand [pattern] into a list of concrete glob patterns.
         *
         * If [pattern] contains no brace groups, returns `listOf(pattern)`.
         * Otherwise returns all cross-product expansions in left-to-right order.
         *
         * The expansion count is computed before anything is materialized, so `{a,b}` repeated 30 times
         * fails fast instead of building 2^30 strings.
         *
         * @throws IllegalArgumentException when [pattern] would expand to more than [maxExpansion] patterns.
         */
        fun expandBraces(
            pattern: String,
            maxExpansion: Int = DEFAULT_MAX_EXPANSION,
        ): List<String> {
            val count = countExpansions(pattern, maxExpansion.toLong() + 1)
            require(count <= maxExpansion) {
                "Brace pattern '$pattern' expands to more than $maxExpansion patterns (limits.glob.maxExpansion)"
            }
            return expandRecursive(pattern)
        }

        /** Number of patterns [pattern] expands to, saturated at [cap] so deep cross-products cannot overflow. */
        private fun countExpansions(
            pattern: String,
            cap: Long,
        ): Long {
            val openIdx = findOutermostOpen(pattern) ?: return 1
            val closeIdx = findMatchingClose(pattern, openIdx) ?: return 1
            val alternatives = splitOnTopLevelCommas(pattern.substring(openIdx + 1, closeIdx))
            val altCount = alternatives.fold(0L) { sum, alt -> (sum + countExpansions(alt, cap)).coerceAtMost(cap) }
            val suffixCount = countExpansions(pattern.substring(closeIdx + 1), cap)
            return if (altCount == 0L || suffixCount <= cap / altCount) altCount * suffixCount else cap
        }

        private fun expandRecursive(pattern: String): List<String> {
            val openIdx = findOutermostOpen(pattern) ?: return listOf(pattern)
            val closeIdx = findMatchingClose(pattern, openIdx) ?: return listOf(pattern)

            val prefix = pattern.substring(0, openIdx)
            val inner = pattern.substring(openIdx + 1, closeIdx)
            val suffix = pattern.substring(closeIdx + 1)

            val alternatives = splitOnTopLevelCommas(inner)

            val results = mutableListOf<String>()
            for (alt in alternatives) {
                // Recursively expand the alternative itself (handles nested braces)
                val expandedAlts = expandRecursive(alt)
                for (expandedAlt in expandedAlts) {
                    // Recursively expand the suffix so that multiple groups produce cross-products
                    val suffixExpansions = expandRecursive(suffix)
                    for (expandedSuffix in suffixExpansions) {
                        results.add(prefix + expandedAlt + expandedSuffix)
                    }
                }
            }
            return results
        }

        /**
         * Returns the index of the first `{` that is not inside a nested brace group,
         * or `null` if no `{` is found.
         */
        private fun findOutermostOpen(pattern: String): Int? {
            for (i in pattern.indices) {
                if (pattern[i] == '{') return i
            }
            return null
        }

        /**
         * Returns the index of the `}` that closes the `{` at [openIdx],
         * respecting nested brace pairs. Returns `null` if unbalanced.
         */
        private fun findMatchingClose(
            pattern: String,
            openIdx: Int,
        ): Int? {
            var depth = 0
            for (i in openIdx until pattern.length) {
                when (pattern[i]) {
                    '{' -> {
                        depth++
                    }

                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return null
        }

        /**
         * Splits [inner] on commas that are at depth 0 (not inside nested braces).
         * Always returns at least one element (the whole string if no comma found).
         */
        private fun splitOnTopLevelCommas(inner: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            for (i in inner.indices) {
                when (inner[i]) {
                    '{' -> depth++

                    '}' -> depth--

                    ',' -> if (depth == 0) {
                        parts.add(inner.substring(start, i))
                        start = i + 1
                    }
                }
            }
            parts.add(inner.substring(start))
            return parts
        }
    }

    /** The same limits rooted at [dir], so a parent config's globs resolve against its own directory. */
    fun withBaseDir(dir: Path): GlobExpander = GlobExpander(dir, maxExpansion, maxDirectoryDepth, logger)

    /** The same base directory under other limits (`limits.glob` inherited by the config declaring the pattern). */
    fun withLimits(
        maxExpansion: Int,
        maxDirectoryDepth: Int,
    ): GlobExpander = GlobExpander(baseDir, maxExpansion, maxDirectoryDepth, logger)

    /**
     * Expand a glob pattern to a sorted list of file paths.
     *
     * @param pattern Glob pattern to expand against [baseDir].
     * @return Sorted list of file paths that match the pattern
     * @throws ConfigException if pattern violates security constraints or expansion fails
     */
    fun expand(pattern: String): List<String> {
        val matches = bracePatterns(pattern).flatMap { expandSingle(it) }.distinct()

        // Security: Enforce max expansion limit
        if (matches.size > maxExpansion) {
            throw ConfigException(
                "${KarandashError.GLOB_EXPANSION_FAILED.bracketCode} Glob pattern '$pattern' expanded to ${matches.size} files " +
                    "(max: $maxExpansion). " +
                    "Use more specific patterns or manifest files (@name references)."
            )
        }

        // Sort results alphabetically for deterministic order
        return matches.sorted()
    }

    /**
     * Expands [pattern] declared in a remote parent config. A remote directory cannot be listed, so only
     * `{a,b}` braces expand, into relative paths the caller resolves against the parent's URL.
     *
     * @throws ConfigException when a `*`, `?` or `[` wildcard is left after brace expansion, or [pattern]
     *   violates the same constraints as [expand].
     */
    fun expandRemote(pattern: String): List<String> {
        val patterns = bracePatterns(pattern)
        if (patterns.any { candidate -> candidate.any { it in WILDCARD_CHARS } }) {
            throw ConfigException(
                "${KarandashError.GLOB_EXPANSION_FAILED.bracketCode} Glob pattern '$pattern' is declared in a remote config: " +
                    "remote directories cannot be listed, so only {a,b} braces expand there. " +
                    "List the files or use manifest files (@name references)."
            )
        }
        return patterns.distinct()
    }

    /** [pattern]'s brace expansions, after the remote URL and path traversal checks. */
    private fun bracePatterns(pattern: String): List<String> {
        // Security: Reject remote URL patterns
        if (isRemoteUrl(pattern)) {
            throw ConfigException(
                "${KarandashError.GLOB_EXPANSION_FAILED.bracketCode} Glob pattern '$pattern' appears to be a remote URL. " +
                    "Remote URLs are not supported in glob patterns. " +
                    "Use manifest files (@name references) for remote configs."
            )
        }

        val patterns = try {
            expandBraces(pattern, maxExpansion)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("${KarandashError.GLOB_EXPANSION_FAILED.bracketCode} ${e.message}", e)
        }

        // Security: reject a `..` segment in any expansion; `..` inside a name such as `v1..2.yaml` is fine
        if (patterns.any { candidate -> candidate.split('/', '\\').any { it == ".." } }) {
            throw ConfigException(
                "${KarandashError.GLOB_SECURITY_VIOLATION.bracketCode} Glob pattern '$pattern' violates security constraints: " +
                    "path traversal detected. " +
                    "Pattern must resolve within project root."
            )
        }
        return patterns
    }

    /** Matches one brace-free pattern under [baseDir]; returns up to `maxExpansion + 1` relative paths. */
    private fun expandSingle(pattern: String): List<String> {
        // Determine the search root from the pattern
        val searchRoot = determineSearchRoot(pattern)
        val searchPath = baseDir.resolve(searchRoot)

        // Security: Validate search path is within baseDir using a string prefix check.
        // The ".." segment check above already blocks traversal; this is a belt-and-suspenders guard.
        // We intentionally avoid SystemFileSystem.resolve()/realpath here: on Linux systems
        // where TMPDIR is a symlink (e.g. /tmp → /private/tmp), resolving the path would
        // return a canonical form that no longer starts with baseDir, causing a false positive.
        if (!isWithin(searchPath.toString(), baseDir.toString())) {
            throw ConfigException(
                e0015ExpandFailed(pattern) +
                    "base directory '$searchRoot' does not exist or path is invalid."
            )
        }

        if (!SystemFileSystem.exists(searchPath)) {
            throw ConfigException(
                e0015ExpandFailed(pattern) +
                    "base directory '$searchRoot' does not exist."
            )
        }

        val matches = mutableListOf<String>()
        try {
            // Resolved once: symlinked entries are compared against the canonical base.
            val absoluteBase = SystemFileSystem.resolve(baseDir).toString()
            walkDirectory(searchPath, pattern, absoluteBase, 0, matches)
        } catch (e: IOException) {
            throw ConfigException(
                e0015ExpandFailed(pattern) + e.message,
                e
            )
        }
        return matches
    }

    /**
     * Recursively walk directory tree and collect matching files.
     *
     * @param dir Directory to walk
     * @param pattern Original glob pattern
     * @param absoluteBase [baseDir] resolved through symlinks
     * @param depth Current recursion depth
     * @param matches Mutable list to collect matching paths
     */
    private fun walkDirectory(
        dir: Path,
        pattern: String,
        absoluteBase: String,
        depth: Int,
        matches: MutableList<String>,
    ) {
        // Security: Enforce max depth
        if (depth > maxDirectoryDepth) {
            return
        }

        // Security: Stop early if we've already exceeded max expansion
        if (matches.size > maxExpansion) {
            return
        }

        val entries = try {
            dir.listFiles() ?: return
        } catch (e: IOException) {
            logger.warn("Glob expansion skipped unreadable directory '$dir': ${e.message}")
            return
        }

        for (entry in entries) {
            // Security: Validate entry is within baseDir (prevent symlink escapes).
            // SystemFileSystem.resolve() / realpath() is the ONLY legitimate place to call
            // realpath - specifically to detect whether a symlink target escapes the project
            // root. This is different from ExternalValueResolver's use-case (building a path
            // to read a user file) where realpath must NOT be called.
            val absoluteEntry = try {
                SystemFileSystem.resolve(entry)
            } catch (ignored: IOException) {
                // Skip entries that can't be resolved
                continue
            }
            if (!isWithin(absoluteEntry.toString(), absoluteBase)) {
                continue // Skip entries outside project root
            }

            val relativePath = getRelativePath(baseDir, entry)

            if (entry.isFile()) {
                if (GlobMatcher.matches(relativePath, pattern)) {
                    matches.add(relativePath)

                    // Security: Stop early if we've hit the limit
                    if (matches.size > maxExpansion) {
                        return
                    }
                }
            } else if (entry.isDirectory() && canMatchBelow(relativePath, pattern)) {
                walkDirectory(entry, pattern, absoluteBase, depth + 1, matches)
            }
        }
    }

    /** True when [candidate] is [base] or lies under it; `/proj-evil` is not under `/proj`. */
    private fun isWithin(
        candidate: String,
        base: String,
    ): Boolean = candidate == base || candidate.startsWith(base.trimEnd('/') + "/")

    /** Without `**` a pattern matches only paths with as many segments as it has, so deeper directories are skipped. */
    private fun canMatchBelow(
        relativeDir: String,
        pattern: String,
    ): Boolean = "**" in pattern || relativeDir.count { it == '/' } < pattern.count { it == '/' }

    /**
     * Determine the base directory from the glob pattern.
     * Returns the portion before the first wildcard character (* or ?).
     */
    private fun determineSearchRoot(pattern: String): String {
        val firstWildcard = pattern.indexOfAny(charArrayOf('*', '?'))
        if (firstWildcard == -1) {
            val lastSlash = pattern.lastIndexOf('/')
            return if (lastSlash == -1) "" else pattern.take(lastSlash)
        }

        val beforeWildcard = pattern.substring(0, firstWildcard)
        val lastSlash = beforeWildcard.lastIndexOf('/')
        return if (lastSlash == -1) "" else beforeWildcard.take(lastSlash)
    }

    /**
     * Get relative path from base to target.
     * Entries from [listFiles] are already absolute; no resolve() needed.
     */
    private fun getRelativePath(
        base: Path,
        target: Path,
    ): String {
        val basePath = base.toString()
        val targetPath = target.toString()

        return if (targetPath.startsWith(basePath)) {
            val relative = targetPath.removePrefix(basePath)
            if (relative.startsWith("/")) relative.substring(1) else relative
        } else {
            targetPath
        }
    }

    private fun e0015ExpandFailed(pattern: String): String =
        "${KarandashError.GLOB_EXPANSION_FAILED.bracketCode} Failed to expand glob pattern '$pattern': "

    /**
     * Check if a pattern looks like a remote URL.
     */
    private fun isRemoteUrl(pattern: String): Boolean =
        pattern.startsWith("http://") ||
            pattern.startsWith("https://") ||
            pattern.startsWith("ftp://") ||
            pattern.startsWith("git://")

    /**
     * Extension to resolve a relative path against this path.
     */
    private fun Path.resolve(other: String): Path =
        if (other.isEmpty()) {
            this
        } else {
            Path(this.toString(), other)
        }
}
