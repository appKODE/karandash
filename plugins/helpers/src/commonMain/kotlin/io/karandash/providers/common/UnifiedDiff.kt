package io.karandash.providers.common

import io.karandash.models.FileChange
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max
import kotlin.math.min

/**
 * Unified-diff helpers for platforms whose API does not return a per-file patch.
 *
 * [FileChange.diffContent] holds hunks only (starting at the first `@@` line), the shape GitHub's `patch` and
 * GitLab's `diff` fields already have.
 */
object UnifiedDiff {
    /**
     * Splits a `git diff` / `git format-patch` text into per-file hunks, keyed by the new path (the old path for
     * deleted files). Files without hunks (binary, mode-only, pure renames) have no entry. Hunk line counts decide
     * where a hunk ends, so trailers such as a format-patch signature are not taken as diff lines. The TAB git
     * appends to a `---`/`+++` name containing a space is dropped.
     */
    fun splitByFile(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val lines = raw.split('\n')
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith(DIFF_GIT)) {
                i++
                continue
            }
            val headerPath = lines[i].substringAfter(" b/", "").ifEmpty { null }
            var oldPath: String? = null
            var newPath: String? = null
            i++
            while (i < lines.size && !lines[i].startsWith(HUNK) && !lines[i].startsWith(DIFF_GIT)) {
                if (lines[i].startsWith(OLD_FILE)) oldPath = fileName(lines[i].removePrefix(OLD_FILE), "a/")
                if (lines[i].startsWith(NEW_FILE)) newPath = fileName(lines[i].removePrefix(NEW_FILE), "b/")
                i++
            }
            val hunks = StringBuilder()
            while (i < lines.size && lines[i].startsWith(HUNK)) {
                i = copyHunk(lines, i, hunks)
            }
            val path = newPath?.takeUnless { it == DEV_NULL } ?: oldPath?.takeUnless { it == DEV_NULL } ?: headerPath
            if (path != null && hunks.isNotEmpty()) result[path] = hunks.toString().trimEnd('\n')
        }
        return result
    }

    /** A `---`/`+++` name without its [prefix]; git C-quotes names with special characters (`"b/a\tb"`). */
    private fun fileName(
        raw: String,
        prefix: String,
    ): String =
        if (raw.length > 1 && raw.startsWith('"') && raw.endsWith('"')) {
            unquoteC(raw.substring(1, raw.length - 1)).removePrefix(prefix)
        } else {
            raw.removePrefix(prefix).removeSuffix("\t")
        }

    /** Decodes git's C-style escapes: `\t \n \" \\` and `\NNN` octal bytes (UTF-8). */
    private fun unquoteC(quoted: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < quoted.length) {
            val c = quoted[i]
            if (c != '\\' || i + 1 >= quoted.length) {
                val width = if (c.isHighSurrogate() && i + 1 < quoted.length) 2 else 1
                bytes += quoted.substring(i, i + width).encodeToByteArray().toList()
                i += width
                continue
            }
            val e = quoted[i + 1]
            if (e in '0'..'7') {
                val digits = quoted.substring(i + 1).takeWhile { it in '0'..'7' }.take(OCTAL_ESCAPE_DIGITS)
                bytes += digits.toInt(OCTAL).toByte()
                i += 1 + digits.length
            } else {
                bytes += (ESCAPES[e] ?: e).toString().encodeToByteArray().toList()
                i += 2
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * Hunks (with [CONTEXT] context lines) that turn [before] into [after]; null when they are equal or either
     * looks binary. Pass `""` for the missing side of an added or deleted file.
     */
    fun between(
        before: String,
        after: String,
    ): String? {
        if (before == after || NUL in before || NUL in after) return null
        val a = toLines(before)
        val b = toLines(after)
        return render(editScript(a, b))
    }

    /**
     * Diffs keyed by path for platforms that only expose file contents (blobs): [contents] returns the
     * before/after text of an entry (`""` for a missing side) or null to skip it. At most
     * [MAX_CONTENT_DIFF_FILES] entries are fetched, [MAX_PARALLEL_FETCHES] at a time.
     */
    suspend fun <T> fromContents(
        entries: List<T>,
        path: (T) -> String,
        contents: suspend (T) -> Pair<String, String>?,
    ): Map<String, String> =
        coroutineScope {
            val permits = Semaphore(MAX_PARALLEL_FETCHES)
            entries
                .take(MAX_CONTENT_DIFF_FILES)
                .map { entry ->
                    async {
                        val (before, after) = permits.withPermit { contents(entry) } ?: return@async null
                        between(before, after)?.let { path(entry) to it }
                    }
                }.awaitAll()
                .filterNotNull()
                .toMap()
        }

    /**
     * Sets [FileChange.diffContent] from [diffs] (keyed by path); changes without an entry are left as they are.
     * A change that reports no line counts (0/0) gets them counted from its diff.
     */
    fun attach(
        changes: List<FileChange>,
        diffs: Map<String, String>,
    ): List<FileChange> =
        changes.map { change ->
            val diff = diffs[change.path] ?: return@map change
            if (change.additions == 0 && change.deletions == 0) {
                val (additions, deletions) = AdapterHelpers.parseDiffStats(diff)
                change.copy(diffContent = diff, additions = additions, deletions = deletions)
            } else {
                change.copy(diffContent = diff)
            }
        }

    private fun copyHunk(
        lines: List<String>,
        start: Int,
        out: StringBuilder,
    ): Int {
        val ranges = lines[start].removePrefix(HUNK).substringBefore(" @@").split(' ')
        var oldLeft = rangeCount(ranges.getOrNull(0))
        var newLeft = rangeCount(ranges.getOrNull(1))
        out.append(lines[start]).append('\n')
        var i = start + 1
        while (i < lines.size && (oldLeft > 0 || newLeft > 0)) {
            when (lines[i].firstOrNull()) {
                '-' -> {
                    oldLeft--
                }

                '+' -> {
                    newLeft--
                }

                '\\' -> {
                    Unit
                }

                else -> {
                    oldLeft--
                    newLeft--
                }
            }
            out.append(lines[i]).append('\n')
            i++
        }
        if (i < lines.size && lines[i].startsWith('\\')) {
            out.append(lines[i]).append('\n')
            i++
        }
        return i
    }

    /** `-12,3` → 3, `+7` → 1. */
    private fun rangeCount(range: String?): Int = range?.substringAfter(',', "1")?.toIntOrNull() ?: 0

    private fun toLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    private class Edit(
        val kind: Char,
        val text: String,
    )

    private fun editScript(
        a: List<String>,
        b: List<String>,
    ): List<Edit> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val oldMid = a.subList(prefix, a.size - suffix)
        val newMid = b.subList(prefix, b.size - suffix)
        return a.subList(0, prefix).map { Edit(' ', it) } +
            middleEdits(oldMid, newMid) +
            a.subList(a.size - suffix, a.size).map { Edit(' ', it) }
    }

    // limit: O(n*m) LCS over the changed middle; past MAX_LCS_CELLS the middle becomes one remove+add block
    // (still a correct diff, just not minimal). Swap in Myers if large rewrites need minimal hunks.
    private fun middleEdits(
        a: List<String>,
        b: List<String>,
    ): List<Edit> {
        if (a.size.toLong() * b.size > MAX_LCS_CELLS) return a.map { Edit('-', it) } + b.map { Edit('+', it) }
        val width = b.size + 1
        val lcs = IntArray((a.size + 1) * width)
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i * width + j] = if (a[i] == b[j]) {
                    lcs[(i + 1) * width + j + 1] + 1
                } else {
                    max(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
                }
            }
        }
        val edits = mutableListOf<Edit>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> edits += Edit(' ', a[i++]).also { j++ }
                j < b.size && (i == a.size || lcs[i * width + j + 1] > lcs[(i + 1) * width + j]) -> edits += Edit('+', b[j++])
                else -> edits += Edit('-', a[i++])
            }
        }
        return edits
    }

    private fun render(edits: List<Edit>): String? {
        val changed = edits.indices.filter { edits[it].kind != ' ' }
        if (changed.isEmpty()) return null
        val groups = mutableListOf(mutableListOf(changed.first()))
        changed.drop(1).forEach { index ->
            if (index - groups.last().last() > 2 * CONTEXT) groups += mutableListOf(index) else groups.last() += index
        }
        return groups.joinToString("\n") { group ->
            val from = max(0, group.first() - CONTEXT)
            val to = min(edits.size - 1, group.last() + CONTEXT)
            val before = edits.subList(0, from)
            val body = edits.subList(from, to + 1)
            val oldStart = before.count { it.kind != '+' } + 1
            val newStart = before.count { it.kind != '-' } + 1
            val header = "@@ -${range(oldStart, body.count { it.kind != '+' })} +${range(newStart, body.count { it.kind != '-' })} @@"
            (listOf(header) + body.map { "${it.kind}${it.text}" }).joinToString("\n")
        }
    }

    private fun range(
        start: Int,
        count: Int,
    ): String = if (count == 0) "${start - 1},0" else "$start,$count"

    private val ESCAPES = mapOf('t' to '\t', 'n' to '\n', 'r' to '\r', 'a' to '\u0007', 'b' to '\b', 'f' to '\u000c', 'v' to '\u000b')

    private const val OCTAL = 8
    private const val OCTAL_ESCAPE_DIGITS = 3
    private const val DIFF_GIT = "diff --git "
    private const val HUNK = "@@ "
    private const val OLD_FILE = "--- "
    private const val NEW_FILE = "+++ "
    private const val DEV_NULL = "/dev/null"
    private const val NUL = '\u0000'
    private const val CONTEXT = 3
    private const val MAX_LCS_CELLS = 4_000_000L
    private const val MAX_CONTENT_DIFF_FILES = 300
    private const val MAX_PARALLEL_FETCHES = 8
}
