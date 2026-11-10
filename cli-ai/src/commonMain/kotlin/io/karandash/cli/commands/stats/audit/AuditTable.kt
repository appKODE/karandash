package io.karandash.cli.commands.stats.audit

import kotlin.math.round

/** Short human-readable summary of an [AuditReport]: one row per split, per-MR means. */
object AuditTable {
    private const val COL_SPLIT = 9
    private const val COL = 10
    private val HEADERS = listOf("MRs", "notes/MR", "dup", "precision", "replied", "outdated", "anchor", "non-code", "coverage")

    fun render(report: AuditReport): String {
        val splits =
            listOfNotNull("all" to report.all, "tune" to report.tune, "test" to report.test, report.holdout?.let { "holdout" to it })
        val header = "split".padEnd(COL_SPLIT) + HEADERS.joinToString("") { it.padStart(COL) }
        val rows = splits.map { (name, m) ->
            val cells = listOf(
                "${m.mrs}",
                fmt(m.notesPerMr.perMr),
                pct(m.duplicateRate.perMr),
                pct(m.precisionProxy.perMr),
                pct(m.replyRate.perMr),
                pct(m.outdatedRate.perMr),
                pct(m.anchorAccuracy.perMr),
                pct(m.nonCodeAnchorRate.perMr),
                pct(m.coverage.perMr),
            )
            name.padEnd(COL_SPLIT) + cells.joinToString("") { it.padStart(COL) }
        }
        val sizes = "summary bytes p50/p95 (all): ${report.all.summaryBytesP50 ?: "-"}/${report.all.summaryBytesP95 ?: "-"}"
        return (listOf(header) + rows + sizes).joinToString("\n")
    }

    /** Two decimals. */
    private fun fmt(x: Double?): String = x?.let { "${round(it * HUNDRED) / HUNDRED}" } ?: "-"

    /** Percent with one decimal. */
    private fun pct(x: Double?): String = x?.let { "${round(it * THOUSAND) / TEN}%" } ?: "-"

    private const val HUNDRED = 100.0
    private const val THOUSAND = 1000.0
    private const val TEN = 10.0
}
