package io.karandash.platform

/**
 * Builds the `CreateProcessW` `lpEnvironment` block (UTF-16, `CREATE_UNICODE_ENVIRONMENT`) for a child that must not
 * inherit some variables: the parent's `NAME=value` entries minus [without], each NUL-terminated, plus the closing NUL.
 * Windows variable names are case-insensitive; entries starting with `=` (per-drive current directories) are kept.
 */
internal object WindowsEnvBlock {
    fun of(
        entries: List<String>,
        without: Set<String>,
    ): String {
        val removed = without.mapTo(mutableSetOf()) { it.uppercase() }
        val kept = entries.filter { entry ->
            val name = entry.substring(0, entry.indexOf('=', startIndex = 1).takeIf { it > 0 } ?: entry.length)
            name.uppercase() !in removed
        }
        return kept.joinToString("") { it + '\u0000' } + '\u0000'
    }
}
