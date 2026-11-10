package io.karandash.utils.mr

import kotlinx.serialization.Serializable

/**
 * Shared unified-diff parser for AI context features. Centralises the hunk-header
 * regex, `@@`-anchored line counting, and the "strip to added lines" projection that
 * shared by `AiRuleAnalyzerUtils` (ai-core), smart truncation and the finding-location validator.
 *
 * The parser is intentionally permissive: malformed input returns a null / empty
 * result, never throws. Callers that need to differentiate "no hunks present" from
 * "not a diff" should check for both [DiffHunk]-list emptiness and the presence of
 * `diff --git` / binary markers themselves.
 */
object HunkParser {
    /** Marker prefix for binary diffs produced by git. */
    const val BINARY_MARKER: String = "Binary files "

    /** Marker prefix for submodule pointer updates. */
    const val SUBMODULE_MARKER: String = "Subproject commit "

    /**
     * Matches unified-diff hunk headers. Captures the new-file start line (group 1)
     * and the new-file count (group 2, optional - defaults to 1 when absent).
     *
     * Accepts one-or-more whitespace separators so both `@@ -1 +1 @@` and the less
     * common tab-delimited variants parse cleanly.
     */
    val HEADER_REGEX: Regex = Regex("""@@\s+-\d+(?:,\d+)?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)""")

    /** One hunk's worth of diff content parsed from a unified diff. */
    @Serializable
    data class DiffHunk(
        /** Full header line as it appeared in the diff (starts with `@@`). */
        val header: String,
        /** Body lines - `+`, `-`, ` ` (context), and `\` pseudo-lines. */
        val lines: List<String>,
        val addedLines: Int,
        val deletedLines: Int,
        val contextLines: Int,
        /** First new-file line number (the `+start` part of the header). */
        val newStartLine: Int,
        /** New-file line count advertised by the header (`+count`, falls back to 1). */
        val newLineCount: Int,
    ) {
        /** Character cost of rendering this hunk (header + body lines + newlines). */
        val totalChars: Int
            get() = header.length + 1 + lines.sumOf { it.length + 1 }

        /** Ratio of added lines relative to the hunk size. */
        val addedDensity: Double
            get() {
                val total = addedLines + deletedLines + contextLines
                return if (total == 0) 0.0 else addedLines.toDouble() / total
            }
    }

    /**
     * Parse [diffContent] into its constituent hunks. Returns an empty list when
     * the input is null, blank, binary, a submodule update, or contains no `@@` headers.
     *
     * The parser recognises standard three-character prefixes (`+`, `-`, ` `) and
     * the `\` "no newline at end of file" pseudo-line.
     */
    fun parseHunks(diffContent: String?): List<DiffHunk> {
        if (diffContent.isNullOrBlank()) return emptyList()
        val trimmed = diffContent.trimStart()
        if (trimmed.startsWith(BINARY_MARKER)) return emptyList()
        if (hasSubmoduleMarker(diffContent)) return emptyList()

        val result = mutableListOf<DiffHunk>()
        var header: String? = null
        var newStart = 0
        var newCount = 1
        var current: MutableList<String> = mutableListOf()
        var added = 0
        var deleted = 0
        var context = 0

        for (line in diffContent.lineSequence()) {
            val match = HEADER_REGEX.find(line)
            if (match != null) {
                if (header != null) {
                    result += DiffHunk(
                        header = header,
                        lines = current.toList(),
                        addedLines = added,
                        deletedLines = deleted,
                        contextLines = context,
                        newStartLine = newStart,
                        newLineCount = newCount,
                    )
                }
                header = line
                newStart = match.groupValues[1].toIntOrNull() ?: 0
                newCount = match.groupValues[2].toIntOrNull() ?: 1
                current = mutableListOf()
                added = 0
                deleted = 0
                context = 0
                continue
            }
            if (header == null) continue
            current += line
            when {
                line.startsWith('+') && !line.startsWith("+++ ") -> added++
                line.startsWith('-') && !line.startsWith("--- ") -> deleted++
                line.startsWith('\\') -> Unit
                else -> context++
            }
        }
        if (header != null) {
            result += DiffHunk(
                header = header,
                lines = current.toList(),
                addedLines = added,
                deletedLines = deleted,
                contextLines = context,
                newStartLine = newStart,
                newLineCount = newCount,
            )
        }
        return result
    }

    /**
     * New-file line numbers covered by `+` lines. Context and deletions are excluded.
     * Empty set for null / blank / binary / submodule / header-less input.
     *
     * Same algorithm as `AiRuleAnalyzerUtils.extractAddedLines`, which delegates here.
     */
    fun extractAddedLineNumbers(diffContent: String?): Set<Int> {
        if (diffContent == null) return emptySet()
        val added = mutableSetOf<Int>()
        var newLine = 0
        for (line in diffContent.lineSequence()) {
            when {
                line.startsWith("@@") -> {
                    val match = HEADER_REGEX.find(line) ?: continue
                    newLine = match.groupValues[1].toIntOrNull() ?: continue
                }

                line.startsWith("+") && !line.startsWith("+++ ") -> {
                    added += newLine
                    newLine++
                }

                line.startsWith("-") && !line.startsWith("--- ") -> {
                    Unit
                }

                line.startsWith("\\") -> {
                    Unit
                }

                else -> {
                    newLine++
                }
            }
        }
        return added
    }

    /**
     * Strip every line except `+` additions and `@@` hunk headers. Context and
     * deletions are removed entirely - preserves new-file line-number accuracy when
     * the output is fed back into line-scanning logic.
     */
    fun stripToAddedLines(diff: String): String =
        diff
            .split('\n')
            .filter { it.startsWith('+') || it.startsWith("@@") }
            .joinToString("\n")

    /**
     * Snap [line] to the nearest added line within [maxSnap] lines. On ties
     * (equal distance in both directions), prefers the forward match.
     *
     * @return the snapped line number, or null if no added line falls within range.
     */
    fun snapToAddedLine(
        line: Int,
        added: Set<Int>,
        maxSnap: Int = DEFAULT_MAX_SNAP,
    ): Int? {
        if (line in added) return line
        for (delta in 1..maxSnap) {
            if (line + delta in added) return line + delta
            if (line - delta in added) return line - delta
        }
        return null
    }

    /**
     * Default search radius used by [snapToAddedLine].
     *
     * Tightened from 5 to 2 in response to observed inline-comment mis-placement:
     * when the LLM reports a line and the nearest added line is more than 2 away,
     * the miss is usually a real mistake (wrong block, wrong hunk) rather than an
     * off-by-one, and silently relocating by 3+ lines hides that error and lands
     * the comment on an unrelated change. Callers that need the old behaviour
     * (tests, specific legacy paths) can pass `maxSnap = 5` explicitly.
     */
    const val DEFAULT_MAX_SNAP: Int = 2

    /**
     * Annotate `+` (added) lines in [diff] with their new-file line number so that an
     * LLM reading the prompt has an explicit anchor instead of having to count `+` rows.
     *
     * Format: `L<n>(+) <original content>` (the leading `+` becomes the tag). Example:
     * ```
     * @@ -300,3 +308,4 @@
     *     context
     * L308(+)   storeId = ProductId(book.storeId),
     * L309(+)   title = book.title,
     * ```
     *
     * Context lines, `-` deletions, `\` pseudo-lines, and non-hunk lines (including
     * `---`, `+++`, `### path` file markers) are returned unchanged. Line-number
     * tracking resets on each `@@` header so the output is correct for multi-file /
     * multi-hunk diffs.
     *
     * Safe to call on a blank diff; returns the input unchanged when no hunks are
     * present.
     */
    fun annotateAddedLinesWithNumbers(diff: String): String {
        if (diff.isBlank()) return diff
        val out = StringBuilder(diff.length + diff.length / CAPACITY_DIVISOR)
        var newLine = 0
        var inHunk = false
        var first = true
        for (line in diff.lineSequence()) {
            if (!first) out.append('\n')
            first = false
            val headerMatch = HEADER_REGEX.find(line)
            when {
                headerMatch != null -> {
                    newLine = headerMatch.groupValues[1].toIntOrNull() ?: 0
                    inHunk = true
                    out.append(line)
                }

                !inHunk -> {
                    out.append(line)
                }

                line.startsWith("+++ ") -> {
                    out.append(line)
                }

                line.startsWith("+") -> {
                    out
                        .append('L')
                        .append(newLine)
                        .append("(+) ")
                        .append(line.substring(1))
                    newLine++
                }

                line.startsWith("--- ") -> {
                    out.append(line)
                }

                line.startsWith("-") -> {
                    out.append(line)
                }

                line.startsWith("\\") -> {
                    out.append(line)
                }

                else -> {
                    out.append(line)
                    newLine++
                }
            }
        }
        return out.toString()
    }

    /** Growth factor for the annotate builder - line-number labels add ~5 chars/line. */
    private const val CAPACITY_DIVISOR: Int = 10

    /**
     * Detect a submodule pointer change. Real submodule diffs from git embed the
     * marker inside a hunk so lines appear as `-Subproject commit <sha>` or
     * `+Subproject commit <sha>`. This helper tolerates the optional `+` / `-`
     * prefix used by unified diff.
     */
    fun hasSubmoduleMarker(diffContent: String): Boolean =
        diffContent.lineSequence().any { line ->
            val body = when {
                line.startsWith("+") && !line.startsWith("+++") -> line.drop(1)
                line.startsWith("-") && !line.startsWith("---") -> line.drop(1)
                else -> line
            }
            body.startsWith(SUBMODULE_MARKER)
        }
}
