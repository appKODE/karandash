package io.karandash.utils.mr

import io.karandash.models.FileChange
import kotlinx.serialization.Serializable

/**
 * A line from a unified diff with its new-file line number.
 *
 * @property lineNumber The 1-based line number in the new (post-change) file.
 * @property content The line content without the leading `+` prefix.
 */
@Serializable
data class DiffLineInfo(
    val lineNumber: Int,
    val content: String,
)

/** A hunk header; anchored, so `@@ … @@` quoted inside an added or context line does not match. */
private val HUNK_HEADER_REGEX = Regex("""^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@""")

/**
 * Diff lines from the first hunk header on. The `---`/`+++` file headers come before it and are dropped, so inside
 * a hunk `+++x` is an added `++x` and `---x` a removed `--x`.
 */
private fun FileChange.hunkLines(): Sequence<String> =
    diffContent
        ?.lineSequence()
        ?.dropWhile { !HUNK_HEADER_REGEX.containsMatchIn(it) }
        .orEmpty()

/**
 * Extract added lines from diff content with their new-file line numbers.
 *
 * Parses unified diff hunk headers (`@@ ... +start,count @@`) to track line numbers,
 * then emits [DiffLineInfo] for each added (`+`) line.
 *
 * @return added lines with their corresponding new-file line numbers, or empty list if no diff
 */
fun FileChange.addedLinesWithNumbers(): List<DiffLineInfo> {
    val result = mutableListOf<DiffLineInfo>()
    var currentLine = 0

    for (line in hunkLines()) {
        val hunkMatch = HUNK_HEADER_REGEX.find(line)
        if (hunkMatch != null) {
            currentLine = hunkMatch.groupValues[2].toInt()
            continue
        }

        when {
            line.startsWith("+") -> {
                result.add(DiffLineInfo(currentLine, line.substring(1)))
                currentLine++
            }

            line.startsWith("-") -> { /* removed line - don't increment new-file counter */ }

            else -> {
                currentLine++
            } // context line
        }
    }
    return result
}

/**
 * The old-file line counter at new-file line [newLine] inside one of the diff's hunks, as GitLab counts it for a diff
 * line code: a context line has its old number, an added line the old number of the line that follows it (the counter
 * is not advanced by `+` lines). `null` when [newLine] is outside every hunk or there is no diff.
 */
fun FileChange.oldLineAt(newLine: Int): Int? {
    var old = 0
    var new = 0
    for (line in hunkLines()) {
        val hunk = HUNK_HEADER_REGEX.find(line)
        when {
            hunk != null -> {
                old = hunk.groupValues[1].toInt()
                new = hunk.groupValues[2].toInt()
            }

            line.startsWith("-") -> {
                old++
            }

            line.startsWith("\\") -> {
                Unit
            }

            new == newLine -> {
                return old
            }

            line.startsWith("+") -> {
                new++
            }

            else -> {
                old++
                new++
            }
        }
    }
    return null
}

/** Added lines of the diff's hunks, without the leading `+`. */
fun FileChange.addedLines(): List<String> = hunkLines().filter { it.startsWith("+") }.map { it.substring(1) }.toList()

/** Removed lines of the diff's hunks, without the leading `-`. */
fun FileChange.removedLines(): List<String> = hunkLines().filter { it.startsWith("-") }.map { it.substring(1) }.toList()

/**
 * Check if diff content contains a pattern
 */
fun FileChange.hasContentMatching(pattern: Regex): Boolean = diffContent?.let { pattern.containsMatchIn(it) } ?: false
