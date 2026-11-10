package io.karandash.patterns

import kotlinx.atomicfu.atomic

/**
 * Recursively expands `@name` pattern references against a [PatternRegistry].
 *
 * ### Supported syntax
 * - `@name` - no-arg reference
 * - `@name(value1, value2)` - positional args mapped to declared [PatternParam] order
 * - `@name(@key:value, @key2:value2)` - named args
 *
 * Expansion is inside-out: any `@references` embedded in an expanded pattern string
 * are resolved before the result is returned. Circular references and depth overflows
 * are detected and reported with `require()`.
 */
object NamedPatternExpander {
    /** Default maximum recursion depth before aborting to prevent infinite loops. */
    const val MAX_DEPTH = 20

    private val installedMaxDepth = atomic(0)

    /** Depth limit in effect: the run's `limits.patterns.maxExpansionDepth` while installed, else [MAX_DEPTH]. */
    val maxDepth: Int get() = installedMaxDepth.value.takeIf { it > 0 } ?: MAX_DEPTH

    /**
     * Installs the run's `limits.patterns.maxExpansionDepth` (already clamped) for the process-wide
     * expander. Pair with [clearMaxDepth] when the run ends.
     */
    fun installMaxDepth(depth: Int) {
        installedMaxDepth.value = depth
    }

    /** Restores [MAX_DEPTH] only if [depth] is still the installed value (compare-and-set). */
    fun clearMaxDepth(depth: Int) {
        installedMaxDepth.compareAndSet(depth, 0)
    }

    /**
     * Expands [pattern] (a full `@name` or `@name(...)` reference) against [registry].
     *
     * @param registry Source of pattern definitions.
     * @param pattern Full pattern reference including the leading `@`.
     * @param visited Names already on the current expansion stack (for cycle detection).
     * @param depth Current recursion depth.
     * @return The fully expanded regex string with all inner `@references` resolved.
     */
    fun expand(
        registry: PatternRegistry,
        pattern: String,
        visited: Set<String> = emptySet(),
        depth: Int = 0,
    ): String {
        val limit = maxDepth
        require(depth < limit) {
            "Pattern expansion depth limit ($limit) exceeded; last pattern: $pattern"
        }
        val name = extractName(pattern)
        require(name !in visited) { "Circular pattern reference detected: @$name" }

        val callerParams = extractParams(registry, pattern, visited, depth)
        val expanded = requireNotNull(registry.resolve(name, callerParams)) {
            "Unknown named pattern: @$name"
        }

        return resolveInnerReferences(registry, expanded, visited + name, depth + 1)
    }

    // ------------------------------------------------------------------
    // Name extraction
    // ------------------------------------------------------------------

    /**
     * Extracts the pattern name from a full reference such as `@kebab-case` or
     * `@separated-list(@key:val, @key2:val2)`.
     *
     * Returns the bare name without the leading `@`.
     */
    internal fun extractName(pattern: String): String {
        val bare = pattern.removePrefix("@")
        val parenIdx = bare.indexOf('(')
        return if (parenIdx >= 0) bare.substring(0, parenIdx) else bare
    }

    // ------------------------------------------------------------------
    // Parameter extraction
    // ------------------------------------------------------------------

    /**
     * Parses the parameter list (if any) from [pattern] and returns a [Map] of
     * parameter name → value.
     *
     * Named arg syntax: `@name(@key:value, @key2:value2)` → `{"key" -> "value", "key2" -> "value2"}`
     * Positional syntax: `@name(val1, val2)` → params matched to declared param order
     * No args: `@name` → empty map
     */
    internal fun extractParams(
        registry: PatternRegistry,
        pattern: String,
        visited: Set<String>,
        depth: Int,
    ): Map<String, String> {
        val bare = pattern.removePrefix("@")
        val parenStart = bare.indexOf('(')
        if (parenStart < 0) return emptyMap()

        require(bare.endsWith(")")) {
            "Malformed pattern reference (missing closing parenthesis): $pattern"
        }
        val argsRaw = bare.substring(parenStart + 1, bare.length - 1).trim()
        if (argsRaw.isEmpty()) return emptyMap()

        val parts = splitArgs(argsRaw)
        return if (parts.all { it.startsWith("@") && it.contains(':') }) {
            parseNamedArgs(parts)
        } else {
            parsePositionalArgs(registry, pattern, parts, visited, depth)
        }
    }

    /**
     * Splits a comma-separated argument list respecting nested parentheses and quotes. An argument (or a named
     * argument's value) that starts with `'` or `"` runs to the matching quote, which is stripped, so `', '` is the
     * two-character separator.
     *
     * Example: `"@key:val, @key2:val2"` → `["@key:val", "@key2:val2"]`; `"@p, ', '"` → `["@p", ", "]`
     */
    private fun splitArgs(argsRaw: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        for (ch in argsRaw) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null
                    current.append(ch)
                }

                (ch == '\'' || ch == '"') && opensValue(current) -> {
                    if (current.isBlank()) current.clear()
                    quote = ch
                    current.append(ch)
                }

                ch == '(' -> {
                    depth++
                    current.append(ch)
                }

                ch == ')' -> {
                    depth--
                    current.append(ch)
                }

                ch == ',' && depth == 0 -> {
                    parts.add(unquote(current.toString().trim()))
                    current.clear()
                }

                else -> {
                    current.append(ch)
                }
            }
        }
        if (current.isNotBlank()) parts.add(unquote(current.toString().trim()))
        return parts
    }

    /** True when a quote read after [current] starts an argument or a named argument's value. */
    private fun opensValue(current: CharSequence): Boolean =
        current.isBlank() || (current.endsWith(':') && current.trimStart().startsWith('@') && current.count { it == ':' } == 1)

    /** [arg] without one pair of enclosing `'…'` or `"…"`. */
    private fun unquote(arg: String): String =
        if (arg.length >= 2 && arg.first() in "'\"" && arg.last() == arg.first()) arg.substring(1, arg.length - 1) else arg

    /**
     * Parses named args of the form `@key:value` into a [Map].
     *
     * The leading `@` and the key are separated by the first `:`.
     */
    private fun parseNamedArgs(parts: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in parts) {
            val colonIdx = part.indexOf(':')
            require(colonIdx > 1) { "Malformed named argument (expected @key:value): $part" }
            val key = part.substring(1, colonIdx).trim() // strip leading '@'
            result[key] = unquote(part.substring(colonIdx + 1).trim())
        }
        return result
    }

    /**
     * Maps positional arg values to declared parameter names from the registry.
     *
     * Each value is itself expanded if it starts with `@`.
     */
    private fun parsePositionalArgs(
        registry: PatternRegistry,
        pattern: String,
        parts: List<String>,
        visited: Set<String>,
        depth: Int,
    ): Map<String, String> {
        val name = extractName(pattern)
        val def = registry.all().find { it.name == name }
        val paramNames = def?.params?.map { it.name } ?: emptyList()
        val result = mutableMapOf<String, String>()
        for ((index, value) in parts.withIndex()) {
            val key = paramNames.getOrNull(index) ?: "arg$index"
            result[key] = if (value.startsWith("@")) {
                expand(registry, value, visited, depth)
            } else {
                value
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // Inner reference resolution
    // ------------------------------------------------------------------

    /**
     * Scans [expanded] for `@name` or `@name(...)` tokens and recursively
     * expands each one, returning the fully-resolved string.
     */
    internal fun resolveInnerReferences(
        registry: PatternRegistry,
        expanded: String,
        visited: Set<String>,
        depth: Int,
    ): String {
        if ('@' !in expanded) return expanded

        val result = StringBuilder()
        var i = 0
        while (i < expanded.length) {
            if (expanded[i] == '@' && expanded.getOrNull(i + 1) in 'a'..'z') {
                val ref = readReference(expanded, i)
                // A body's `@` that names no pattern (`renovate@bot\.example`) is literal text; only a top-level
                // reference must exist.
                result.append(if (registry.exists(extractName(ref))) expand(registry, ref, visited, depth) else ref)
                i += ref.length
            } else {
                result.append(expanded[i])
                i++
            }
        }
        return result.toString()
    }

    /**
     * Reads a complete `@name` or `@name(...)` token starting at [start] in [s],
     * respecting nested parentheses.
     */
    private fun readReference(
        s: String,
        start: Int
    ): String {
        var i = start + 1 // skip '@'
        // Read identifier characters: letters, digits, hyphens, underscores
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
        // Optionally consume a parenthesized argument list
        if (i < s.length && s[i] == '(') {
            var depth = 1
            i++
            while (i < s.length && depth > 0) {
                when (s[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
        }
        return s.substring(start, i)
    }
}
