package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Developer growth report: tracks per-developer quality trajectory over time.
 *
 * Splits the chronologically sorted run history into two halves and compares average
 * finding counts to determine whether quality is improving, declining, or stable.
 */
object DeveloperGrowthReportGenerator {
    private const val HALF_SPLIT = 2
    private const val PERCENT_MULTIPLIER = 100.0
    private const val PERSISTENT_RULE_THRESHOLD = 0.5
    private const val IMPROVEMENT_THRESHOLD = 10.0

    /**
     * Generates a [DeveloperGrowthData] report for the given author.
     *
     * @param authorId Developer identifier (e.g. username or email).
     * @param records Historical run records attributed to this developer, in any order.
     * @return A [DeveloperGrowthData] describing the developer's quality trajectory.
     */
    fun generate(
        authorId: String,
        records: List<RunRecord>
    ): DeveloperGrowthData {
        if (records.isEmpty()) return DeveloperGrowthData(authorId = authorId)

        val sorted = records.sortedBy { it.timestamp }
        val midpoint = sorted.size / HALF_SPLIT
        val firstHalf = sorted.take(midpoint.coerceAtLeast(1))
        val secondHalf = sorted.drop(midpoint.coerceAtLeast(1))

        val firstAvg = firstHalf.map { it.totalFindings }.average()
        val secondAvg = if (secondHalf.isNotEmpty()) {
            secondHalf.map { it.totalFindings }.average()
        } else {
            firstAvg
        }

        val improvementPercent = if (firstAvg > 0) {
            (firstAvg - secondAvg) / firstAvg * PERCENT_MULTIPLIER
        } else {
            0.0
        }

        val ruleFireCounts = mutableMapOf<String, Int>()
        for (record in records) {
            for ((rule, count) in record.findingsByRule) {
                if (count > 0) ruleFireCounts[rule] = (ruleFireCounts[rule] ?: 0) + 1
            }
        }
        val persistentRules = ruleFireCounts
            .filter { it.value.toDouble() / records.size > PERSISTENT_RULE_THRESHOLD }
            .keys
            .toList()

        return DeveloperGrowthData(
            authorId = authorId,
            totalRuns = records.size,
            averageFindingsPerRun = records.map { it.totalFindings }.average(),
            improvementPercent = improvementPercent,
            trend = when {
                improvementPercent > IMPROVEMENT_THRESHOLD -> GrowthTrend.IMPROVING
                improvementPercent < -IMPROVEMENT_THRESHOLD -> GrowthTrend.DECLINING
                else -> GrowthTrend.STABLE
            },
            persistentProblemRules = persistentRules,
        )
    }

    /**
     * Formats a [DeveloperGrowthData] report as a human-readable Markdown block.
     *
     * @param data The developer growth data to format.
     * @param language `report.language` of the fixed texts; null is English.
     * @return A Markdown string summarising trend, run count, and persistent issues.
     */
    fun formatReport(
        data: DeveloperGrowthData,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.GROWTH_TITLE.of(language, data.authorId))
            appendLine(
                AiReportText.GROWTH_TREND.of(language, AiReportText.label(data.trend.name, language), data.improvementPercent.roundToInt()),
            )
            appendLine(AiReportText.GROWTH_RUNS.of(language, data.totalRuns, data.averageFindingsPerRun.formatDecimals(1)))
            if (data.persistentProblemRules.isNotEmpty()) {
                appendLine(AiReportText.GROWTH_PERSISTENT.of(language, data.persistentProblemRules.joinToString(", ")))
            }
        }
}

/**
 * Per-developer quality trajectory data.
 *
 * @param authorId Developer identifier.
 * @param totalRuns Number of runs analysed.
 * @param averageFindingsPerRun Mean total findings per run.
 * @param improvementPercent Percentage reduction in findings from first half to second half
 *   of runs. Positive = improving, negative = declining.
 * @param trend Categorical growth trend derived from [improvementPercent].
 * @param persistentProblemRules Rule IDs that fired in more than 50% of runs.
 */
@Serializable
data class DeveloperGrowthData(
    val authorId: String = "",
    val totalRuns: Int = 0,
    val averageFindingsPerRun: Double = 0.0,
    val improvementPercent: Double = 0.0,
    val trend: GrowthTrend = GrowthTrend.STABLE,
    val persistentProblemRules: List<String> = emptyList(),
)

/** Categorical quality trajectory for a developer. */
enum class GrowthTrend { IMPROVING, STABLE, DECLINING }
