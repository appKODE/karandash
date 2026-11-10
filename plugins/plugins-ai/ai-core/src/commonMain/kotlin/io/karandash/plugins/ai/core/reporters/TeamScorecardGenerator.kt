package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Team hygiene scorecard: scores quality across rule categories with trend arrows.
 *
 * Computes a composite score and per-rule scores from historical [RunRecord] data.
 * Scores are in the range [0, 100]; each finding deducts [PENALTY_PER_WARNING] points,
 * floored at [MIN_SCORE].
 */
object TeamScorecardGenerator {
    private const val PERFECT_SCORE = 100
    private const val PENALTY_PER_WARNING = 3
    private const val MIN_SCORE = 0

    /**
     * Generates a [TeamScorecard] from the given run records.
     *
     * @param records Historical validation run records.
     * @return A [TeamScorecard]; defaults to a perfect score when [records] is empty.
     */
    fun generate(records: List<RunRecord>): TeamScorecard {
        if (records.isEmpty()) return TeamScorecard()

        val totalRuns = records.size
        val ruleScores = mutableMapOf<String, RuleScore>()

        val allRules = records.flatMap { it.findingsByRule.keys }.toSet()
        for (rule in allRules) {
            val fireCount = records.count { (it.findingsByRule[rule] ?: 0) > 0 }
            val totalFindings = records.sumOf { it.findingsByRule[rule] ?: 0 }
            val fireRate = fireCount.toDouble() / totalRuns
            val score = (PERFECT_SCORE - totalFindings * PENALTY_PER_WARNING).coerceAtLeast(MIN_SCORE)

            ruleScores[rule] = RuleScore(
                ruleId = rule,
                score = score,
                fireRate = fireRate,
                totalFindings = totalFindings,
            )
        }

        val compositeScore = if (ruleScores.isEmpty()) {
            PERFECT_SCORE
        } else {
            ruleScores.values
                .map { it.score }
                .average()
                .toInt()
        }

        return TeamScorecard(
            compositeScore = compositeScore,
            ruleScores = ruleScores,
            totalRuns = totalRuns,
            totalFindings = records.sumOf { it.totalFindings },
        )
    }

    /**
     * Formats a [TeamScorecard] as a human-readable Markdown report.
     *
     * @param scorecard The scorecard to format.
     * @param language `report.language` of the fixed texts; null is English.
     * @return A Markdown string with a per-rule table sorted by score ascending (worst first).
     */
    fun formatReport(
        scorecard: TeamScorecard,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.SCORECARD_TITLE.of(language))
            appendLine(AiReportText.SCORECARD_COMPOSITE.of(language, scorecard.compositeScore))
            appendLine(AiReportText.SCORECARD_RUNS.of(language, scorecard.totalRuns, scorecard.totalFindings))
            appendLine()
            appendLine(AiReportText.SCORECARD_HEADER.of(language))
            appendLine("|------|-------|-----------|----------|")
            for ((rule, score) in scorecard.ruleScores.entries.sortedBy { it.value.score }) {
                appendLine(
                    "| $rule | ${score.score}/100 | ${(score.fireRate * PERCENT_MULTIPLIER).roundToInt()}% | ${score.totalFindings} |"
                )
            }
        }

    private const val PERCENT_MULTIPLIER = 100
}

/**
 * Scorecard summarising team code quality across all rule categories.
 *
 * @param compositeScore Average rule score across all observed rules (0–100).
 * @param ruleScores Per-rule breakdown keyed by rule ID.
 * @param totalRuns Number of runs included in the scorecard.
 * @param totalFindings Total finding count across all runs.
 */
@Serializable
data class TeamScorecard(
    val compositeScore: Int = 100,
    val ruleScores: Map<String, RuleScore> = emptyMap(),
    val totalRuns: Int = 0,
    val totalFindings: Int = 0,
)

/**
 * Score for a single rule within a [TeamScorecard].
 *
 * @param ruleId The rule identifier.
 * @param score Quality score in [0, 100]; 100 means no findings for this rule.
 * @param fireRate Fraction of runs in which this rule fired (0.0–1.0).
 * @param totalFindings Total number of findings produced by this rule.
 */
@Serializable
data class RuleScore(
    val ruleId: String,
    val score: Int,
    val fireRate: Double,
    val totalFindings: Int,
)
