package io.karandash.cache

/**
 * Determines which storage tiers a cache uses.
 */
enum class CacheTier {
    /** In-memory only; entries are lost on process exit. */
    MEMORY_ONLY,

    /** In-memory with file-based persistence for cross-run survival. */
    MEMORY_AND_FILE,
}
