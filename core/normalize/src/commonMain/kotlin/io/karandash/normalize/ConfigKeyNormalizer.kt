package io.karandash.normalize

/**
 * Converts config key strings between kebab-case and camelCase.
 *
 * For format **conversion** (kebab <-> camelCase), use this object.
 * For **matching/lookup** where any naming convention should resolve,
 * use [ModuleIdNormalizer.normalize] instead.
 */
object ConfigKeyNormalizer {
    /**
     * Converts a kebab-case or snake_case string to camelCase.
     *
     * Keys that contain no dashes or underscores are returned unchanged. This is
     * the canonical conversion used throughout the config parsing pipeline so that
     * YAML kebab-case keys (e.g. `title-format`) and snake_case keys (e.g.
     * `allow_localhost`) round-trip correctly to the camelCase keys expected by
     * rule modules (e.g. `titleFormat`, `allowLocalhost`).
     *
     * @param key The input string, which may be kebab-case, snake_case, camelCase, or plain.
     * @return The camelCase form of [key].
     */
    fun kebabToCamelCase(key: String): String {
        if (!key.contains('-') && !key.contains('_')) return key
        return buildString {
            var capitalizeNext = false
            for (ch in key) {
                if (ch == '-' || ch == '_') {
                    capitalizeNext = true
                } else {
                    append(if (capitalizeNext) ch.uppercaseChar() else ch)
                    capitalizeNext = false
                }
            }
        }
    }

    /**
     * Converts a camelCase string to kebab-case.
     *
     * Each uppercase character is preceded by a dash and lowercased. Already
     * lowercase strings are returned unchanged.
     *
     * @param key The input camelCase string.
     * @return The kebab-case form of [key].
     */
    fun camelToKebabCase(key: String): String =
        buildString {
            for (ch in key) {
                if (ch.isUpperCase()) {
                    if (isNotEmpty()) append('-')
                    append(ch.lowercaseChar())
                } else {
                    append(ch)
                }
            }
        }
}
