package io.karandash.config

/**
 * Centralized limits and thresholds used across the Karandash codebase.
 *
 * These are compile-time defaults. At runtime, the `limits:` config block
 * (parsed into `LimitsConfig`) can override these values. Pipeline code
 * that supports runtime configuration should accept limits via constructor
 * parameters defaulting to these constants.
 */
object KarandashLimits {
    /** Maximum number of compiled regex patterns cached by GlobMatcher. */
    const val MAX_CACHE_SIZE = 1000

    /** Maximum length of a condition expression string. */
    const val MAX_EXPRESSION_LENGTH = 1000

    /** Maximum length of a regex pattern in condition expressions. */
    const val MAX_REGEX_LENGTH = 200

    /** Maximum nesting depth of condition expression AST. */
    const val MAX_NESTING_DEPTH = 50

    /** Maximum number of findings before truncation. */
    const val MAX_FINDINGS = 10_000

    /** Maximum number of entries in the extends chain. */
    const val MAX_EXTENDS_ENTRIES = 10

    /** Maximum inheritance depth for config extends. */
    const val MAX_CHAIN_DEPTH = 5

    /** Maximum total chain size for config extends. */
    const val MAX_CHAIN_SIZE = 20

    /** Maximum number of files a glob pattern can expand to. */
    const val MAX_GLOB_EXPANSION = 10

    /** Maximum directory depth for recursive glob expansion. */
    const val MAX_DIRECTORY_DEPTH = 5

    /** Remote config fetch timeout in milliseconds. */
    const val FETCH_TIMEOUT_MS = 10_000L

    /** Default TTL for in-memory caches, in seconds (30 minutes). */
    const val DEFAULT_CACHE_TTL_SECONDS = 1800L

    /** Default HTTP request/connect/socket timeout in seconds for provider API calls. */
    const val DEFAULT_HTTP_TIMEOUT_SECONDS = 60L
}
