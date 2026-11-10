package io.karandash.plugins.outputs

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.helpers.reporter.isMrWhere

/**
 * Drops from the summary table the rows an inline thread already shows this run, so a finding is not listed twice.
 * A row is matched by the `<!-- karandash-row <fp> -->` the markdown format ends it with
 * ([InlineFindingMarker.ROW_PREFIX]); headline counts are left alone.
 */
internal object SummaryRows {
    /**
     * [body] without the rows of the findings in [covered] (inline comments posted this run or matched to an open
     * thread), each finding dropping at most one row. The table is followed by `+N in inline comments` in [language], or
     * replaced by it when no row is left; the `Where` column goes too when every row left says only `MR`. [body]
     * unchanged when nothing matched.
     */
    fun withoutInline(
        body: String,
        covered: List<InlineCommentData>,
        language: String?,
    ): String {
        val left = covered
            .flatMap { c -> InlineFindingMarker.parse(c.body).map { it.semanticFingerprint } }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val lines = body.lines()
        val dropped = lines.indices.filter { i ->
            val fp = rowFingerprint(lines[i]) ?: return@filter false
            val n = left[fp] ?: 0
            if (n > 0) left[fp] = n - 1
            n > 0
        }
        if (dropped.isEmpty()) return body
        val first = dropped.first()
        val start = (first downTo 0).takeWhile { lines[it].startsWith("|") }.last()
        val end = (first until lines.size).takeWhile { lines[it].startsWith("|") }.last()
        val notice = "_${InlineLifecycleText.INLINE_ONLY.of(language, dropped.size)}_"
        val keptRows = (start..end).count { it !in dropped } - TABLE_HEAD_LINES
        val table = if (keptRows > 0) {
            withoutMrOnlyWhere((start..end).filter { it !in dropped }.map { lines[it] }) + listOf("", notice)
        } else {
            listOf(notice)
        }
        val after = lines.drop(end + 1)
        val gap = if (after.firstOrNull()?.isNotBlank() == true) listOf("") else emptyList()
        return (lines.take(start) + table + gap + after).joinToString("\n")
    }

    /** [table] (header, separator, rows) without its second, `Where` cell when every row's says only `MR`. */
    private fun withoutMrOnlyWhere(table: List<String>): List<String> {
        if (!table.drop(TABLE_HEAD_LINES).all { isMrWhere(cell(it, WHERE_CELL)) }) return table
        return table.map { line ->
            val from = nthPipe(line, WHERE_CELL)
            line.removeRange(from, nthPipe(line, WHERE_CELL + 1))
        }
    }

    /** The text of cell [n] (0-based) of a `| a | b |` row: the icon and `Where` cells hold no `|`. */
    private fun cell(
        line: String,
        n: Int,
    ): String = line.substring(nthPipe(line, n) + 1, nthPipe(line, n + 1))

    private fun nthPipe(
        line: String,
        n: Int,
    ): Int = (0..n).fold(-1) { at, _ -> line.indexOf('|', at + 1) }

    private fun rowFingerprint(line: String): String? {
        val at = line.indexOf(InlineFindingMarker.ROW_PREFIX)
        if (at < 0 || !line.startsWith("|")) return null
        return line.substring(at + InlineFindingMarker.ROW_PREFIX.length).substringBefore(" -->")
    }

    /** The header row and the `|---|` separator. */
    private const val TABLE_HEAD_LINES = 2

    /** The `Where` cell: after the severity icon. */
    private const val WHERE_CELL = 1
}
