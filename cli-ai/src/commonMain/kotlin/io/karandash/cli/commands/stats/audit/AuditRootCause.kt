package io.karandash.cli.commands.stats.audit

import kotlinx.serialization.Serializable
import kotlin.math.round

/**
 * What the run behind an MR's findings looked like, read from the provenance block of its latest bot summary.
 * [cause]: `context-cut` (context left out by the budget), `diff-cut` (files left out, chunks skipped or truncated),
 * `enricher-failed`, `none`, or `unknown` when the summary carries no provenance. [context]: the `ok` enricher ids
 * joined by `+` (`none` when none was ok, `unknown` when the summary has no enricher table). [size]: bucket of the MR's total file count.
 */
@Serializable
data class ClusterContext(
    val cause: String = UNKNOWN,
    val context: String = UNKNOWN,
    val size: String = UNKNOWN,
) {
    companion object {
        const val UNKNOWN = "unknown"
    }
}

/** Labelled clusters of one cause × analyzer × context × size cell; precision = accepted / (accepted + rejected). */
@Serializable
data class RootCauseRow(
    val cause: String,
    val analyzer: String,
    val context: String,
    val size: String,
    val accepted: Int,
    val rejected: Int,
    val precision: Double,
)

/** Joins audit labels with run provenance so `stats --audit` can say why findings were rejected. */
object AuditRootCause {
    /**
     * The coverage label of the provenance block, as `MarkdownTransparency` writes it for each summary language:
     * the `PROVENANCE_COVERAGE` values of `SummaryText` (markdown-format, which cli-ai must not depend on).
     * Everything else the parser reads is language-neutral or already listed in [OK] / [NOT_OK].
     */
    private val COVERAGE_LABELS = listOf("coverage", "покрытие", "pokrycie").map { "**$it:**" }
    private const val ROW_START = "| `"
    private const val ENRICHER_CELLS = 3
    private val OK = setOf("ok", "ок")
    private val NOT_OK = setOf("skipped", "пропущено", "pominięto", "failed", "ошибка", "błąd")
    private val SIZES = listOf(5 to "S", 20 to "M", 50 to "L")
    private const val XL = "XL"

    /** [ClusterContext] of a summary [body]; all `unknown` without a provenance coverage line. */
    fun context(body: String?): ClusterContext {
        val lines = body?.lines().orEmpty()
        val coverage = lines
            .map { it.trimStart().lowercase() }
            .firstOrNull { l -> COVERAGE_LABELS.any { l.startsWith(it) } } ?: return ClusterContext()
        val enrichers = lines.mapNotNull(::enricherRow)
        val total = StatsAuditRunner.coverageCounts(coverage)?.second
        val parts = coverage.split(", ")
        val diffCut = ", left out: " in coverage || count(parts, " skipped") > 0 || count(parts, " truncated") > 0
        val cause = when {
            "context left out: " in coverage -> "context-cut"
            diffCut -> "diff-cut"
            enrichers.any { it.second in NOT_OK } -> "enricher-failed"
            else -> "none"
        }
        val ok = enrichers.filter { it.second in OK }.map { it.first }.sorted()
        val size = total?.let { t -> SIZES.firstOrNull { t <= it.first }?.second ?: XL } ?: ClusterContext.UNKNOWN
        return ClusterContext(cause, ok.joinToString("+").ifEmpty { if (enrichers.isEmpty()) ClusterContext.UNKNOWN else "none" }, size)
    }

    /** One row per cell that has accepted or rejected clusters, sorted by cause, analyzer, context, size. */
    fun breakdown(mrs: List<MrAudit>): List<RootCauseRow> =
        mrs
            .flatMap { m -> m.clusters.map { m.context to it } }
            .filter { it.second.label == ClusterLabel.ACCEPTED || it.second.label == ClusterLabel.REJECTED }
            .groupBy { (ctx, c) -> listOf(ctx.cause, c.rule, ctx.context, ctx.size) }
            .map { (key, cells) ->
                val accepted = cells.count { it.second.label == ClusterLabel.ACCEPTED }
                val rejected = cells.size - accepted
                RootCauseRow(key[0], key[1], key[2], key[3], accepted, rejected, accepted.toDouble() / cells.size)
            }.sortedWith(compareBy({ it.cause }, { it.analyzer }, { it.context }, { it.size }))

    private const val COL_CAUSE = 16
    private const val COL_ANALYZER = 16
    private const val COL_CONTEXT = 22
    private const val COL_SIZE = 4
    private const val COL_NUM = 10
    private const val THOUSAND = 1000.0
    private const val TEN = 10.0

    fun render(rows: List<RootCauseRow>): String {
        val header = "cause".padEnd(COL_CAUSE) + "analyzer".padEnd(COL_ANALYZER) + "context".padEnd(COL_CONTEXT) +
            "size".padEnd(COL_SIZE) + listOf("accepted", "rejected", "precision").joinToString("") { it.padStart(COL_NUM) }
        val body = rows.map { r ->
            r.cause.padEnd(COL_CAUSE) + r.analyzer.padEnd(COL_ANALYZER) + r.context.padEnd(COL_CONTEXT) + r.size.padEnd(COL_SIZE) +
                listOf(
                    "${r.accepted}",
                    "${r.rejected}",
                    "${round(r.precision * THOUSAND) / TEN}%"
                ).joinToString("") { it.padStart(COL_NUM) }
        }
        return (listOf(header) + body).joinToString("\n")
    }

    /** `(id, status)` of an enricher table row `` | `id` | status | details | ``; analyzer rows have 5 cells. */
    private fun enricherRow(line: String): Pair<String, String>? {
        val row = line.trim()
        if (!row.startsWith(ROW_START)) return null
        val cells = row
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
        return if (cells.size == ENRICHER_CELLS) cells[0].trim('`') to cells[1].lowercase() else null
    }

    /** Leading number of the first part ending in [suffix], else 0. */
    private fun count(
        parts: List<String>,
        suffix: String,
    ): Int = parts.firstOrNull { it.endsWith(suffix) }?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}
