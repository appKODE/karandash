package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.HunkExpansionConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `ai.contextQuality.hunk-expansion`: widens each hunk of a diff with context lines from the file's head content
 * (`FileChange.newContent`), so the model sees the code around a change, not git's three lines.
 *
 * A file of at most [HunkExpansionConfig.wholeFileMaxLines] lines is sent whole, its changed lines still marked
 * `+`/`-`. A bigger file gets each hunk widened to its enclosing block, at most [HunkExpansionConfig.maxHunkLines]
 * lines per hunk. The result is still a unified diff with correct `@@` headers, so `L<n>(+)` line labels, the
 * chunker and the diff caps work on it unchanged. A diff that does not match the head content is left as it is.
 */
object HunkExpander {
    private val HEADER = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)$""")

    /** True when the plugin [config] turns `contextQuality.hunk-expansion` on, so the head content gets fetched. */
    fun isActive(config: JsonElement): Boolean {
        val quality = (config as? JsonObject)?.let { it["contextQuality"] ?: it["context-quality"] } as? JsonObject
        val block = quality?.let { it["hunk-expansion"] ?: it["hunkExpansion"] } as? JsonObject
        return (quality?.get("enabled") as? JsonPrimitive)?.booleanOrNull != false &&
            (block?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
    }

    /** [change] with its diff widened, or [change] itself when there is no head content or nothing to widen. */
    fun expand(
        change: FileChange,
        config: HunkExpansionConfig,
    ): FileChange {
        val diff = change.diffContent
        val content = change.newContent
        if (!config.enabled || diff == null || content == null || change.changeType == ChangeType.DELETED) return change
        val expanded = expand(diff, content.removeSuffix("\n").split('\n'), config)
        return if (expanded == diff) change else change.copy(diffContent = expanded)
    }

    /**
     * Per hunk of [diff], the changed new-file lines (`changed`) and the enclosing block around them (`block`, at most
     * [maxLines]); null when [diff] has no hunks or does not match [lines].
     */
    internal fun windows(
        diff: String,
        lines: List<String>,
        maxLines: Int,
    ): List<Pair<IntRange, IntRange>>? {
        val hunks = parse(diff.removeSuffix("\n")).second
        if (hunks.isEmpty() || hunks.any { !it.matches(lines) }) return null
        return hunks.map { (it.first..it.last) to window(lines, it, maxLines) }
    }

    internal fun expand(
        diff: String,
        lines: List<String>,
        config: HunkExpansionConfig,
    ): String {
        val (prefix, hunks) = parse(diff.removeSuffix("\n"))
        if (hunks.isEmpty() || hunks.any { !it.matches(lines) }) return diff
        val whole = lines.size <= config.wholeFileMaxLines
        val windows = hunks.map { if (whole) 1..lines.size else window(lines, it, config.maxHunkLines) }
        val out = prefix.toMutableList()
        var prevHi = 0
        hunks.forEachIndexed { i, hunk ->
            val lo = maxOf(windows[i].first, prevHi + 1).coerceAtMost(hunk.first)
            val hi = minOf(windows[i].last, (hunks.getOrNull(i + 1)?.first ?: Int.MAX_VALUE) - 1).coerceAtLeast(hunk.last)
            out += hunk.rendered(lines, lo, hi)
            prevHi = hi
        }
        return out.joinToString("\n") + if (diff.endsWith('\n')) "\n" else ""
    }

    /**
     * The new-file lines around [hunk] that make up its enclosing block, capped at [maxLines].
     *
     * limit: indentation heuristic, not a parser. The block starts at the nearest line above that is indented less
     * than the changed lines and ends at the first line back at that indent (kept when it closes with `}`, `)`, `]` or
     * `end`). Wrongly indented code, one-line functions and multi-line signatures get a partial or extra window;
     * swap in a tree-sitter outline (SymbolContextEnricher's provider) if that matters.
     */
    private fun window(
        lines: List<String>,
        hunk: Hunk,
        maxLines: Int,
    ): IntRange {
        val extra = maxLines - (hunk.last - hunk.first + 1)
        if (extra <= 0) return hunk.first..hunk.last
        val changed = (hunk.first..hunk.last).map { lines[it - 1] }.filter { it.isNotBlank() }
        val minIndent = changed.minOfOrNull(::indent) ?: lines.getOrNull(hunk.first - 2)?.let(::indent) ?: 0
        val header = (hunk.first - 1 downTo 1).firstOrNull { lines[it - 1].isNotBlank() && indent(lines[it - 1]) < minIndent }
        // An Allman `{` on its own line belongs to the signature above it.
        val start = header?.let { if (lines[it - 1].trim() == "{" && it > 1) it - 1 else it } ?: hunk.first
        val headerIndent = header?.let { indent(lines[it - 1]) } ?: minIndent
        val end = blockEnd(lines, hunk.last, headerIndent)
        val lo = maxOf(start, hunk.first - extra / 2)
        val hi = minOf(end, hunk.last + extra - (hunk.first - lo))
        return lo..hi
    }

    private fun blockEnd(
        lines: List<String>,
        last: Int,
        headerIndent: Int,
    ): Int {
        val next = (last + 1..lines.size).firstOrNull { lines[it - 1].isNotBlank() && indent(lines[it - 1]) <= headerIndent }
            ?: return lines.size
        val closer = lines[next - 1].trim()
        return if (CLOSERS.any { closer.startsWith(it) }) next else next - 1
    }

    private fun indent(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') TAB_WIDTH else 1 }

    private fun parse(diff: String): Pair<List<String>, List<Hunk>> {
        val prefix = mutableListOf<String>()
        val hunks = mutableListOf<Pair<List<String>, MutableList<String>>>()
        for (line in diff.split('\n')) {
            val match = HEADER.find(line)
            when {
                match != null -> hunks += match.groupValues to mutableListOf()
                hunks.isEmpty() -> prefix += line
                else -> hunks.last().second += line
            }
        }
        return prefix to hunks.map { (header, body) -> Hunk(header, body.toList()) }
    }

    /** One hunk: its header numbers and body lines; [first]..[last] are the new-file lines it covers. */
    private class Hunk(
        header: List<String>,
        private val body: List<String>,
    ) {
        private val oldStart = header[1].toInt()
        private val oldCount = header[2].toIntOrNull() ?: 1
        private val newStart = header[3].toInt()
        private val newCount = header[4].toIntOrNull() ?: 1
        private val section = header[5]

        /** A `+N,0` hunk sits after line N. */
        val first: Int get() = if (newCount == 0) newStart + 1 else newStart
        val last: Int get() = first + newCount - 1

        private val newLines: List<String> get() = body.filter { it.startsWith(' ') || it.startsWith('+') || it.isEmpty() }

        /** Every context and added line equals the head content at its position. */
        fun matches(lines: List<String>): Boolean =
            newLines.size == newCount &&
                newLines.withIndex().all { (i, line) -> lines.getOrNull(first + i - 1) == line.drop(1) }

        fun rendered(
            lines: List<String>,
            lo: Int,
            hi: Int,
        ): List<String> {
            val before = (lo until first).map { " ${lines[it - 1]}" }
            val after = (last + 1..hi).map { " ${lines[it - 1]}" }
            val oldFirst = if (oldCount == 0) oldStart + 1 else oldStart
            val added = before.size + after.size
            val head = "@@ -${oldFirst - before.size},${oldCount + added} +$lo,${newCount + added} @@$section"
            return listOf(head) + before + body + after
        }
    }

    private val CLOSERS = listOf("}", ")", "]", "end")
    private const val TAB_WIDTH = 4
}
