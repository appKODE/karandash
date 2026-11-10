package io.karandash.cache

/**
 * Computes deterministic hashes from dependency maps for cache staleness detection.
 *
 * Dependencies are sorted by key and length-prefixed to avoid ambiguity
 * between values. The resulting string is hashed to a compact hex representation.
 */
internal object DependencyHash {
    /**
     * Computes a stable hash string from a dependency map.
     *
     * Keys are sorted lexicographically. Each entry is length-prefixed
     * to prevent collisions between keys/values with special characters.
     *
     * @param dependencies Map of dependency names to their current string values.
     * @return A hex-encoded hash string.
     */
    fun compute(dependencies: Map<String, String>): String {
        if (dependencies.isEmpty()) return EMPTY_HASH
        val parts = dependencies.entries
            .sortedBy { it.key }
            .joinToString(SEPARATOR) { (k, v) ->
                "${k.length}:$k=${v.length}:$v"
            }
        return parts.hashCode().toUInt().toString(HEX_RADIX)
    }

    private const val SEPARATOR = "|"
    private const val HEX_RADIX = 16
    private const val EMPTY_HASH = "0"
}
