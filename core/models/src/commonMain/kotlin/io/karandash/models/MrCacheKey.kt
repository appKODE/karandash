package io.karandash.models

/**
 * Canonical builder for MR-scoped cache directory keys.
 *
 * Every component that derives a filesystem path from a [MergeRequest] must use
 * [buildKey] so that all caches resolve to the same directory.
 */
object MrCacheKey {
    private val INVALID_CHARS_REGEX = Regex("[^a-zA-Z0-9-]")

    /**
     * Returns a stable, filesystem-safe key for [mr].
     *
     * Format: `mr-{id}` with non-alphanumeric characters (except `-`) replaced by `-`.
     *
     * @throws IllegalArgumentException if [mr.id] is blank.
     */
    fun buildKey(mr: MergeRequest): String {
        require(mr.id.isNotBlank()) { "MergeRequest.id must not be blank for cache key generation" }
        return "mr-${mr.id}".replace(INVALID_CHARS_REGEX, "-")
    }
}
