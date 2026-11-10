package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Flaky rule detector: identifies rules with inconsistent behavior.
 *
 * Detects:
 * - Flip-floppers: rules that alternate between firing and not firing
 * - Never-fire: rules that have never produced findings
 * - Always-fire: rules that fire on every single run
 */
object FlakyRuleDetector {
    private const val MIN_RUNS = 5
    private const val FLAKY_THRESHOLD = 0.3
    private const val ALWAYS_FIRE_THRESHOLD = 0.95
    private const val NEVER_FIRE_RUNS = 10

    /**
     * Analyzes [records] and returns a [FlakyRuleReport] describing any problematic rules.
     *
     * Returns an empty report when fewer than [MIN_RUNS] records are available.
     *
     * @param records Historical run records, oldest-first.
     * @param language The `report.language` of the issue descriptions; null renders English.
     * @return Report listing each detected issue sorted by fire rate descending.
     */
    fun detect(
        records: List<RunRecord>,
        language: String? = null,
    ): FlakyRuleReport {
        if (records.size < MIN_RUNS) return FlakyRuleReport()

        val allRules = records.flatMap { it.findingsByRule.keys }.toSet()
        val issues = mutableListOf<FlakyRuleIssue>()

        for (rule in allRules) {
            val firingPattern = records.map { (it.findingsByRule[rule] ?: 0) > 0 }
            val fireRate = firingPattern.count { it }.toDouble() / records.size

            var transitions = 0
            for (i in 1 until firingPattern.size) {
                if (firingPattern[i] != firingPattern[i - 1]) transitions++
            }
            val transitionRate = transitions.toDouble() / (records.size - 1).coerceAtLeast(1)

            when {
                transitionRate > FLAKY_THRESHOLD -> issues.add(
                    FlakyRuleIssue(
                        ruleId = rule,
                        issueType = FlakyIssueType.FLIP_FLOP,
                        description = AiReportText.FLAKY_FLIP_FLOP_DESCRIPTION.of(language, (transitionRate * PERCENT).roundToInt()),
                        fireRate = fireRate,
                    ),
                )

                fireRate == 0.0 && records.size >= NEVER_FIRE_RUNS -> issues.add(
                    FlakyRuleIssue(
                        ruleId = rule,
                        issueType = FlakyIssueType.NEVER_FIRES,
                        description = AiReportText.FLAKY_NEVER_FIRES_DESCRIPTION.of(language, records.size),
                        fireRate = 0.0,
                    ),
                )

                fireRate >= ALWAYS_FIRE_THRESHOLD -> issues.add(
                    FlakyRuleIssue(
                        ruleId = rule,
                        issueType = FlakyIssueType.ALWAYS_FIRES,
                        description = AiReportText.FLAKY_ALWAYS_FIRES_DESCRIPTION.of(language, (fireRate * PERCENT).roundToInt()),
                        fireRate = fireRate,
                    ),
                )
            }
        }

        return FlakyRuleReport(
            issues = issues.sortedByDescending { it.fireRate },
            rulesAnalyzed = allRules.size,
            runsAnalyzed = records.size,
        )
    }

    /**
     * Formats a [FlakyRuleReport] as a human-readable Markdown string.
     *
     * @param report The report to render.
     * @param language The `report.language` of the fixed texts; null renders English.
     * @return Markdown string with issue count and per-rule descriptions.
     */
    fun formatReport(
        report: FlakyRuleReport,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.FLAKY_TITLE.of(language))
            appendLine(AiReportText.FLAKY_COUNTS.of(language, report.rulesAnalyzed, report.runsAnalyzed))
            if (report.issues.isEmpty()) {
                appendLine(AiReportText.FLAKY_NONE.of(language))
            } else {
                appendLine(AiReportText.FLAKY_ISSUES.of(language, report.issues.size))
                appendLine()
                for (issue in report.issues) {
                    appendLine("- [${AiReportText.label(issue.issueType.name, language)}] ${issue.ruleId}: ${issue.description}")
                }
            }
        }

    private const val PERCENT = 100
}

/**
 * Result of a flaky-rule analysis pass.
 *
 * @param issues List of detected issues, sorted by fire rate descending.
 * @param rulesAnalyzed Total number of distinct rules seen across all records.
 * @param runsAnalyzed Number of run records analyzed.
 */
@Serializable
data class FlakyRuleReport(
    val issues: List<FlakyRuleIssue> = emptyList(),
    val rulesAnalyzed: Int = 0,
    val runsAnalyzed: Int = 0,
)

/**
 * Describes a single problematic rule detected by [FlakyRuleDetector].
 *
 * @param ruleId Rule identifier.
 * @param issueType Category of the detected issue.
 * @param description Human-readable explanation.
 * @param fireRate Fraction of runs in which this rule produced at least one finding.
 */
@Serializable
data class FlakyRuleIssue(
    val ruleId: String,
    val issueType: FlakyIssueType,
    val description: String,
    val fireRate: Double,
)

/** Categories of flaky-rule issues detected by [FlakyRuleDetector]. */
enum class FlakyIssueType { FLIP_FLOP, NEVER_FIRES, ALWAYS_FIRES }
