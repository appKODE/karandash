package io.karandash.cli.commands.calibrate

import io.karandash.cli.commands.stats.audit.ClusterLabel
import io.karandash.cli.commands.stats.audit.ClusterMatcher
import io.karandash.cli.commands.stats.audit.MrAudit
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable
import kotlin.math.round

/** What `calibrate --corpus` proposes for one analyzer. */
@Serializable
enum class AnalyzerAction {
    KEEP,

    /** One severity level down, via `ai.severity-overrides`. */
    DOWNGRADE,

    /** Already at the lowest severity (or unknown): raise `ai.filter.min-confidence` instead. */
    RAISE_MIN_CONFIDENCE,
}

/** Precision of one analyzer over its accepted/rejected clusters, the severity it posted with and the proposal. */
@Serializable
data class AnalyzerProposal(
    val analyzer: String,
    val accepted: Int,
    val rejected: Int,
    val precision: Double,
    val severity: String?,
    val action: AnalyzerAction,
    val proposedSeverity: String? = null,
)

/** Per-analyzer precision from `stats --audit` labels and the severity changes it suggests; nothing is applied. */
object AnalyzerCalibration {
    /** Below this precision an analyzer is proposed one level down. */
    const val LOW_PRECISION = 0.5

    // limit: fixed floor of labelled clusters per analyzer; make it a flag if corpora outgrow it
    const val MIN_LABELLED = 3

    fun proposals(mrs: List<MrAudit>): List<AnalyzerProposal> =
        mrs
            .flatMap { it.clusters }
            .filter { it.rule != ClusterMatcher.UNKNOWN_RULE }
            .filter { it.label == ClusterLabel.ACCEPTED || it.label == ClusterLabel.REJECTED }
            .groupBy { it.rule }
            .map { (rule, clusters) ->
                val accepted = clusters.count { it.label == ClusterLabel.ACCEPTED }
                val precision = accepted.toDouble() / clusters.size
                val severity = clusters
                    .mapNotNull { it.severity }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedWith(compareBy({ -it.value }, { it.key }))
                    .firstOrNull()
                    ?.key
                val lower = severity?.let { s -> Severity.entries.getOrNull(Severity.valueOf(s).ordinal + 1)?.name }
                val action = when {
                    clusters.size < MIN_LABELLED || precision >= LOW_PRECISION -> AnalyzerAction.KEEP
                    lower != null -> AnalyzerAction.DOWNGRADE
                    else -> AnalyzerAction.RAISE_MIN_CONFIDENCE
                }
                val proposed = lower.takeIf { action == AnalyzerAction.DOWNGRADE }
                AnalyzerProposal(rule, accepted, clusters.size - accepted, precision, severity, action, proposed)
            }.sortedWith(compareBy({ it.precision }, { it.analyzer }))

    fun render(proposals: List<AnalyzerProposal>): String =
        buildString {
            append("Analyzer precision (stats --audit labels)\n")
            append("analyzer".padEnd(COL_NAME) + "accepted".padStart(COL_NUM) + "rejected".padStart(COL_NUM))
            append("precision".padStart(COL_NUM) + "  " + "severity".padEnd(COL_SEVERITY) + "proposal\n")
            proposals.forEach { p ->
                append(p.analyzer.padEnd(COL_NAME) + "${p.accepted}".padStart(COL_NUM) + "${p.rejected}".padStart(COL_NUM))
                append(pct(p.precision).padStart(COL_NUM) + "  " + (p.severity ?: "-").padEnd(COL_SEVERITY) + describe(p) + "\n")
            }
            val downgrades = proposals.filter { it.action == AnalyzerAction.DOWNGRADE }
            val raises = proposals.filter { it.action == AnalyzerAction.RAISE_MIN_CONFIDENCE }
            if (downgrades.isEmpty() && raises.isEmpty()) return@buildString
            append("\nProposal (not applied): review it, then add it to the project config yourself\n")
            if (downgrades.isNotEmpty()) append("ai:\n  severity-overrides:\n")
            downgrades.forEach { p ->
                append("    - rule: ${p.analyzer}\n")
                append("      reclassify: {${p.severity?.lowercase()}: ${p.proposedSeverity?.lowercase()}}\n")
                append("      reason: \"calibrate: precision ${pct(p.precision)} on ${p.accepted + p.rejected} labelled findings\"\n")
            }
            if (raises.isNotEmpty()) {
                append("Raise ai.filter.min-confidence (default 0.3) for: ${raises.joinToString(", ") { it.analyzer }}\n")
            }
        }.trimEnd()

    private fun describe(p: AnalyzerProposal): String =
        when (p.action) {
            AnalyzerAction.KEEP -> if (p.accepted + p.rejected < MIN_LABELLED) "keep (under $MIN_LABELLED labels)" else "keep"
            AnalyzerAction.DOWNGRADE -> "downgrade to ${p.proposedSeverity}"
            AnalyzerAction.RAISE_MIN_CONFIDENCE -> "raise ai.filter.min-confidence"
        }

    private fun pct(x: Double): String = "${round(x * THOUSAND) / TEN}%"

    private const val COL_NAME = 24
    private const val COL_NUM = 10
    private const val COL_SEVERITY = 10
    private const val THOUSAND = 1000.0
    private const val TEN = 10.0
}
