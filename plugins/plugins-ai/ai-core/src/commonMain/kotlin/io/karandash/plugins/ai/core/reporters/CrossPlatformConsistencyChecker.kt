package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable

/**
 * Cross-platform consistency checker: compares validation results across platforms.
 * Detects policy drift between GitLab, GitHub, Bitbucket, etc.
 */
object CrossPlatformConsistencyChecker {
    /**
     * Compares per-platform [RunRecord] lists and identifies policy drift.
     *
     * Two platforms are considered drifted when the absolute difference in their
     * average finding counts exceeds [DRIFT_THRESHOLD]. A report with a single
     * platform (or no platforms) is always marked consistent with no drifts.
     *
     * @param platformRecords Map of platform name to its list of run records.
     * @param language The `report.language` of the drift descriptions; null renders English.
     * @return [ConsistencyReport] with per-platform stats and any detected drifts.
     */
    fun compare(
        platformRecords: Map<String, List<RunRecord>>,
        language: String? = null,
    ): ConsistencyReport {
        if (platformRecords.size < MIN_PLATFORMS_FOR_COMPARISON) {
            return ConsistencyReport(platforms = platformRecords.keys.toList())
        }

        val platformStats = platformRecords.map { (platform, records) ->
            val avgFindings = if (records.isNotEmpty()) records.map { it.totalFindings }.average() else 0.0
            val avgErrors = if (records.isNotEmpty()) records.map { it.errorCount }.average() else 0.0
            PlatformStats(platform = platform, avgFindings = avgFindings, avgErrors = avgErrors, runs = records.size)
        }

        val drifts = mutableListOf<PolicyDrift>()
        for (i in platformStats.indices) {
            for (j in i + 1 until platformStats.size) {
                val a = platformStats[i]
                val b = platformStats[j]
                val findingDiff = kotlin.math.abs(a.avgFindings - b.avgFindings)
                if (findingDiff > DRIFT_THRESHOLD) {
                    drifts.add(
                        PolicyDrift(
                            platformA = a.platform,
                            platformB = b.platform,
                            findingDelta = findingDiff,
                            description = AiReportText.CONSISTENCY_DRIFT_DESCRIPTION.of(
                                language,
                                a.platform,
                                a.avgFindings.toInt(),
                                b.platform,
                                b.avgFindings.toInt(),
                            ),
                        ),
                    )
                }
            }
        }

        return ConsistencyReport(
            platforms = platformRecords.keys.toList(),
            platformStats = platformStats,
            drifts = drifts,
            isConsistent = drifts.isEmpty(),
        )
    }

    /**
     * Formats a [ConsistencyReport] as a human-readable Markdown summary.
     *
     * @param report The report to render.
     * @param language The `report.language` of the fixed texts; null renders English.
     * @return Markdown string listing platforms, consistency status, and any drifts.
     */
    fun formatReport(
        report: ConsistencyReport,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.CONSISTENCY_TITLE.of(language))
            appendLine(AiReportText.CONSISTENCY_PLATFORMS.of(language, report.platforms.joinToString(", ")))
            appendLine((if (report.isConsistent) AiReportText.CONSISTENCY_YES else AiReportText.CONSISTENCY_NO).of(language))
            if (report.drifts.isNotEmpty()) {
                appendLine()
                for (drift in report.drifts) {
                    appendLine(AiReportText.CONSISTENCY_DRIFT.of(language, drift.description))
                }
            }
        }

    private const val DRIFT_THRESHOLD = 2.0
    private const val MIN_PLATFORMS_FOR_COMPARISON = 2
}

/**
 * Result of a cross-platform consistency comparison.
 *
 * @param platforms Platform names that were compared.
 * @param platformStats Per-platform aggregate statistics.
 * @param drifts Detected policy drifts between platform pairs.
 * @param isConsistent True when no drifts were detected.
 */
@Serializable
data class ConsistencyReport(
    val platforms: List<String> = emptyList(),
    val platformStats: List<PlatformStats> = emptyList(),
    val drifts: List<PolicyDrift> = emptyList(),
    val isConsistent: Boolean = true,
)

/**
 * Aggregate statistics for a single platform's run history.
 *
 * @param platform Platform name (e.g., `gitlab`, `github`).
 * @param avgFindings Average total findings per run.
 * @param avgErrors Average error-severity findings per run.
 * @param runs Number of run records used for the averages.
 */
@Serializable
data class PlatformStats(
    val platform: String,
    val avgFindings: Double,
    val avgErrors: Double,
    val runs: Int,
)

/**
 * Describes a detected policy drift between two platforms.
 *
 * @param platformA First platform in the pair.
 * @param platformB Second platform in the pair.
 * @param findingDelta Absolute difference in average findings between the two platforms.
 * @param description Human-readable drift description.
 */
@Serializable
data class PolicyDrift(
    val platformA: String,
    val platformB: String,
    val findingDelta: Double,
    val description: String,
)
