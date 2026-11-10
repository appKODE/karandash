package io.karandash.cli.commands.stats.audit

import kotlinx.serialization.Serializable
import kotlin.math.round
import kotlin.random.Random

/** Label and reply state of one cluster, with the analyzer ([rule]), file and note-header severity it was posted with. */
@Serializable
data class ClusterOutcome(
    val label: ClusterLabel,
    val hasReply: Boolean,
    val rule: String = ClusterMatcher.UNKNOWN_RULE,
    val path: String = "",
    val severity: String? = null,
)

/** Everything the metrics need from one MR. */
@Serializable
data class MrAudit(
    val project: String,
    val iid: Int,
    /** All bot-authored, non-system notes. */
    val botNotes: Int = 0,
    /** Bot inline notes (the findings that get clustered). */
    val findingNotes: Int = 0,
    /** MR versions. */
    val pushes: Int = 0,
    val clusters: List<ClusterOutcome> = emptyList(),
    val anchorChecks: List<AnchorResult> = emptyList(),
    /** reviewed/total files stated by a bot summary note, if any. */
    val coverage: Double? = null,
    /** UTF-8 byte size of each bot summary (non-inline) note. */
    val summaryBytes: List<Int> = emptyList(),
    /** Run provenance of the latest bot summary that carries one. */
    val context: ClusterContext = ClusterContext(),
)

/** A metric averaged over MRs ([perMr], with a bootstrap 95% CI) and over projects ([perProject]); [n] MRs had a value. */
@Serializable
data class MetricValue(
    val perMr: Double?,
    val perProject: Double?,
    val ciLow: Double?,
    val ciHigh: Double?,
    val n: Int,
)

/** Cluster count per label. */
@Serializable
data class LabelCounts(
    val accepted: Int = 0,
    val rejected: Int = 0,
    val outdated: Int = 0,
    val unknown: Int = 0,
    val botResolved: Int = 0,
)

/** Metrics of one split. Ratios are per-MR values, then averaged; see [AuditMetrics]. */
@Serializable
data class SplitMetrics(
    val mrs: Int,
    val projects: Int,
    val botNotes: Int,
    val findingNotes: Int,
    val clusters: Int,
    val labels: LabelCounts,
    val notesPerMr: MetricValue,
    val notesPerPush: MetricValue,
    val duplicateRate: MetricValue,
    val precisionProxy: MetricValue,
    val replyRate: MetricValue,
    val precisionLower: MetricValue,
    val precisionUpper: MetricValue,
    val outdatedRate: MetricValue,
    val anchorAccuracy: MetricValue,
    val nonCodeAnchorRate: MetricValue,
    val coverage: MetricValue,
    val summaryBytesP50: Int?,
    val summaryBytesP95: Int?,
)

/**
 * Metric definitions, per MR (an MR without the denominator has no value and is skipped):
 * notes per MR = bot notes; notes per push = bot notes / versions; duplicate rate = 1 - clusters / inline notes;
 * precision proxy = accepted / (accepted + rejected), with reply rate (clusters with a human reply / clusters),
 * lower bound accepted / clusters and upper bound (accepted + unknown) / clusters; outdated rate = outdated / clusters;
 * anchor accuracy = accurate / code-quoting inline notes; non-code anchor rate = non-code anchor lines / checked
 * inline notes; coverage = stated reviewed/total files. Summary size percentiles pool all summary notes.
 */
object AuditMetrics {
    const val DEFAULT_RESAMPLES = 1000

    fun compute(
        mrs: List<MrAudit>,
        seed: Int,
        resamples: Int = DEFAULT_RESAMPLES,
    ): SplitMetrics {
        val metric = { f: (MrAudit) -> Double? -> value(mrs, seed, resamples, f) }
        val sizes = mrs.flatMap { it.summaryBytes }.sorted()
        return SplitMetrics(
            mrs = mrs.size,
            projects = mrs.map { it.project }.distinct().size,
            botNotes = mrs.sumOf { it.botNotes },
            findingNotes = mrs.sumOf { it.findingNotes },
            clusters = mrs.sumOf { it.clusters.size },
            labels = labelCounts(mrs.flatMap { it.clusters }),
            notesPerMr = metric { it.botNotes.toDouble() },
            notesPerPush = metric { m -> ratio(m.botNotes, m.pushes) },
            duplicateRate = metric { m -> ratio(m.clusters.size, m.findingNotes)?.let { 1 - it } },
            precisionProxy = metric { m ->
                ratio(
                    m.count(ClusterLabel.ACCEPTED),
                    m.count(ClusterLabel.ACCEPTED) + m.count(ClusterLabel.REJECTED)
                )
            },
            replyRate = metric { m -> ratio(m.clusters.count { it.hasReply }, m.clusters.size) },
            precisionLower = metric { m -> ratio(m.count(ClusterLabel.ACCEPTED), m.clusters.size) },
            precisionUpper = metric { m -> ratio(m.count(ClusterLabel.ACCEPTED) + m.count(ClusterLabel.UNKNOWN), m.clusters.size) },
            outdatedRate = metric { m -> ratio(m.count(ClusterLabel.OUTDATED), m.clusters.size) },
            anchorAccuracy = metric { m ->
                val quoting = m.anchorChecks.filter { it.quotesCode }
                ratio(quoting.count { it.accurate }, quoting.size)
            },
            nonCodeAnchorRate = metric { m -> ratio(m.anchorChecks.count { it.nonCodeLine }, m.anchorChecks.size) },
            coverage = metric { it.coverage },
            summaryBytesP50 = percentile(sizes, P50),
            summaryBytesP95 = percentile(sizes, P95),
        )
    }

    private const val P50 = 50
    private const val P95 = 95

    private fun value(
        mrs: List<MrAudit>,
        seed: Int,
        resamples: Int,
        f: (MrAudit) -> Double?,
    ): MetricValue {
        val values = mrs.map(f)
        val present = values.filterNotNull()
        val perProject = mrs
            .zip(values)
            .filter { it.second != null }
            .groupBy({ it.first.project }, { checkNotNull(it.second) })
            .values
            .map { it.average() }
        val ci = Bootstrap.meanCi(values, seed, resamples)
        return MetricValue(
            perMr = present.takeIf { it.isNotEmpty() }?.average()?.let(::round4),
            perProject = perProject.takeIf { it.isNotEmpty() }?.average()?.let(::round4),
            ciLow = ci?.first,
            ciHigh = ci?.second,
            n = present.size,
        )
    }

    private fun MrAudit.count(label: ClusterLabel): Int = clusters.count { it.label == label }

    private fun ratio(
        num: Int,
        den: Int,
    ): Double? = if (den > 0) num.toDouble() / den else null

    private fun labelCounts(clusters: List<ClusterOutcome>): LabelCounts {
        val by = clusters.groupingBy { it.label }.eachCount()
        return LabelCounts(
            accepted = by[ClusterLabel.ACCEPTED] ?: 0,
            rejected = by[ClusterLabel.REJECTED] ?: 0,
            outdated = by[ClusterLabel.OUTDATED] ?: 0,
            unknown = by[ClusterLabel.UNKNOWN] ?: 0,
            botResolved = by[ClusterLabel.BOT_RESOLVED] ?: 0,
        )
    }

    /** Nearest-rank percentile of an ascending list. */
    private fun percentile(
        sorted: List<Int>,
        p: Int,
    ): Int? = if (sorted.isEmpty()) null else sorted[((p * sorted.size + PERCENT - 1) / PERCENT - 1).coerceAtLeast(0)]

    private const val PERCENT = 100
}

/** Percentile bootstrap over MRs, seeded so the same corpus and seed give the same interval. */
object Bootstrap {
    private const val LOW_PER_MILLE = 25
    private const val HIGH_PER_MILLE = 975
    private const val PER_MILLE = 1000

    /** 95% CI of the mean of the non-null [values] when MRs are resampled; `null` when no MR has a value. */
    fun meanCi(
        values: List<Double?>,
        seed: Int,
        resamples: Int,
    ): Pair<Double, Double>? {
        if (values.none { it != null }) return null
        val random = Random(seed)
        val means = (1..resamples)
            .mapNotNull {
                values.indices
                    .mapNotNull { values[random.nextInt(values.size)] }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
            }.sorted()
        if (means.isEmpty()) return null
        val last = means.size - 1
        val low = means[last * LOW_PER_MILLE / PER_MILLE]
        val high = means[(last * HIGH_PER_MILLE + PER_MILLE - 1) / PER_MILLE]
        return round4(low) to round4(high)
    }
}

/** Rounds to 4 decimals so the JSON report is stable across platforms. */
internal fun round4(x: Double): Double = round(x * ROUNDING) / ROUNDING

private const val ROUNDING = 10_000.0
