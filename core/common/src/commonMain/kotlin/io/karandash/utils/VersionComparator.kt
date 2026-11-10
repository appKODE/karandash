package io.karandash.utils

/**
 * Semantic version comparison utility.
 *
 * Supports versions in major.minor.patch format (e.g., "1.2.3").
 * Missing components default to 0 (e.g., "1.2" is treated as "1.2.0").
 * Handles version range prefixes (`^`, `~`, `>=`, etc.) by stripping them. [compare] orders a pre-release
 * (`1.0.0-rc1`) below its release and pre-releases among themselves as semver 11.4 does; `+build` metadata is ignored.
 */
object VersionComparator {
    private const val RANGE_PREFIX_CHARS = "^~><=!"

    /**
     * Compare two semantic version strings.
     *
     * @return negative if [v1] < [v2], 0 if equal, positive if [v1] > [v2]
     */
    fun compare(
        v1: String,
        v2: String,
    ): Int {
        val parts1 = parse(v1)
        val parts2 = parse(v2)

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return comparePrerelease(prerelease(v1), prerelease(v2))
    }

    private fun prerelease(version: String): List<String> =
        version
            .substringBefore('+')
            .substringAfter('-', "")
            .split('.')
            .filter { it.isNotEmpty() }

    /** No pre-release ranks above any; numeric identifiers rank below alphanumeric ones; a longer list wins a tie. */
    private fun comparePrerelease(
        pre1: List<String>,
        pre2: List<String>,
    ): Int {
        if (pre1.isEmpty() || pre2.isEmpty()) return pre2.size.compareTo(pre1.size).coerceIn(-1, 1)
        val first = pre1.zip(pre2).firstOrNull { (a, b) -> a != b } ?: return pre1.size.compareTo(pre2.size)
        val (n1, n2) = first.first.toLongOrNull() to first.second.toLongOrNull()
        return when {
            n1 != null && n2 != null -> n1.compareTo(n2)
            n1 != null -> -1
            n2 != null -> 1
            else -> first.first.compareTo(first.second)
        }
    }

    /**
     * Check whether [candidate] is a newer version than [current].
     */
    fun isNewer(
        current: String,
        candidate: String,
    ): Boolean = compare(candidate, current) > 0

    /**
     * Check whether [current] is strictly below [minimum].
     *
     * Returns `false` when either version has no parseable numeric components.
     */
    fun isBelow(
        current: String,
        minimum: String,
    ): Boolean {
        val curParts = parseStrict(current) ?: return false
        val minParts = parseStrict(minimum) ?: return false
        for (i in 0 until maxOf(curParts.size, minParts.size)) {
            val c = curParts.getOrElse(i) { 0 }
            val m = minParts.getOrElse(i) { 0 }
            if (c < m) return true
            if (c > m) return false
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        normalize(version)
            .split(".")
            .map { it.toIntOrNull() ?: 0 }

    /**
     * Strict parse that returns `null` when any component is non-numeric.
     */
    private fun parseStrict(version: String): List<Int>? {
        val parts = normalize(version).split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun normalize(version: String): String {
        val withoutVPrefix = version.trimStart('v', 'V')
        val withoutRange = withoutVPrefix.dropWhile { it in RANGE_PREFIX_CHARS }
        return withoutRange.substringBefore('-').substringBefore('+')
    }
}
