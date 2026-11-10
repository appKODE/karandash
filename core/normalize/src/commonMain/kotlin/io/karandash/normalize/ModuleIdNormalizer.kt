package io.karandash.normalize

/**
 * Normalizes module identifiers to a canonical form (lowercase alphanumeric)
 * for case-insensitive, separator-insensitive matching.
 *
 * For format **conversion** (kebab <-> camelCase), use [ConfigKeyNormalizer] instead.
 * For **matching/lookup**, use this normalizer so that any naming convention
 * (kebab-case, snake_case, camelCase, PascalCase) resolves to the same canonical form.
 */
object ModuleIdNormalizer {
    /**
     * Strips all non-alphanumeric characters and lowercases the result.
     *
     * @param value The raw identifier string to normalize.
     * @return The normalized identifier (lowercase, alphanumeric only).
     */
    fun normalize(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
            }
        }

    /**
     * Returns true if [a] and [b] normalize to the same canonical form.
     *
     * @param a First identifier.
     * @param b Second identifier.
     * @return Whether the two identifiers match after normalization.
     */
    fun matches(
        a: String,
        b: String,
    ): Boolean = normalize(a) == normalize(b)

    /**
     * Resolves [id] to the entry of [candidates] it names: the exact entry when present,
     * otherwise the first one that [matches] it, otherwise null.
     *
     * Use it to canonicalize a user-written id (policy `modules:`, profile rule lists,
     * override targets) to the configured key once, so later exact lookups agree.
     *
     * @param id The raw identifier to resolve.
     * @param candidates The canonical keys to resolve against (e.g. configured rule keys).
     * @return The matching candidate, or null when none matches.
     */
    fun resolve(
        id: String,
        candidates: Collection<String>,
    ): String? {
        if (id in candidates) return id
        val normalized = normalize(id)
        return candidates.firstOrNull { normalize(it) == normalized }
    }
}
