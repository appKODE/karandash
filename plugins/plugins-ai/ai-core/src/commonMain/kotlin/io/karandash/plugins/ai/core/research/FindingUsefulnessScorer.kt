package io.karandash.plugins.ai.core.research

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Finding usefulness scorer based on Microsoft Research taxonomy (Bosu et al., MSR 2015).
 *
 * Classifies findings as useful/not-useful based on content analysis:
 * - Useful: defects, logical issues, missing validation, security vulnerabilities.
 * - Not useful: style, formatting, naming conventions (these belong to linters).
 */
object FindingUsefulnessScorer {
    private val USEFUL_KEYWORDS = setOf(
        "bug",
        "defect",
        "error",
        "null",
        "crash",
        "exception",
        "security",
        "vulnerability",
        "injection",
        "leak",
        "race",
        "deadlock",
        "overflow",
        "missing",
        "validation",
        "unchecked",
        "unsafe",
        "incorrect",
        "wrong",
        "broken",
        "undefined",
        "unhandled",
        "infinite",
        "timeout",
        "corrupt",
    )

    private val NOT_USEFUL_KEYWORDS = setOf(
        "formatting",
        "indentation",
        "whitespace",
        "naming",
        "convention",
        "style",
        "cosmetic",
        "typo",
        "spelling",
        "capitalization",
        "comment",
        "documentation",
        "javadoc",
        "kdoc",
        "trailing",
        "spacing",
    )

    private const val USEFUL_THRESHOLD = 2
    private const val NOT_USEFUL_THRESHOLD = -1
    private const val ERROR_SEVERITY_BONUS = 2
    private const val WARNING_SEVERITY_BONUS = 1

    /**
     * Scores a single finding for usefulness.
     *
     * @param finding The finding to classify.
     * @return [UsefulnessScore] with classification and keyword hit counts.
     */
    fun score(finding: Finding): UsefulnessScore {
        val message = finding.message.lowercase()
        val explanation = (finding.explanation ?: "").lowercase()
        val combined = "$message $explanation"

        val usefulHits = USEFUL_KEYWORDS.count { combined.contains(it) }
        val notUsefulHits = NOT_USEFUL_KEYWORDS.count { combined.contains(it) }

        val severityBonus = when (finding.severity) {
            Severity.ERROR -> ERROR_SEVERITY_BONUS
            Severity.WARNING -> WARNING_SEVERITY_BONUS
            Severity.INFO -> 0
        }

        val rawScore = usefulHits + severityBonus - notUsefulHits
        val classification = when {
            rawScore >= USEFUL_THRESHOLD -> UsefulnessLevel.USEFUL
            rawScore <= NOT_USEFUL_THRESHOLD -> UsefulnessLevel.NOT_USEFUL
            else -> UsefulnessLevel.UNCERTAIN
        }

        return UsefulnessScore(
            finding = finding,
            level = classification,
            usefulKeywordHits = usefulHits,
            notUsefulKeywordHits = notUsefulHits,
            rawScore = rawScore,
        )
    }

    /**
     * Scores all findings in a list.
     *
     * @param findings The findings to classify.
     * @return List of [UsefulnessScore] in the same order as [findings].
     */
    fun scoreAll(findings: List<Finding>): List<UsefulnessScore> = findings.map { score(it) }

    /**
     * Filters a list of findings, retaining only those not classified as [UsefulnessLevel.NOT_USEFUL].
     *
     * @param findings The findings to filter.
     * @return Findings classified as [UsefulnessLevel.USEFUL] or [UsefulnessLevel.UNCERTAIN].
     */
    fun filterUseful(findings: List<Finding>): List<Finding> = findings.filter { score(it).level != UsefulnessLevel.NOT_USEFUL }
}

/**
 * Usefulness classification for a single finding.
 *
 * @param finding The original finding.
 * @param level The usefulness classification.
 * @param usefulKeywordHits Number of useful keywords matched in the finding text.
 * @param notUsefulKeywordHits Number of not-useful keywords matched in the finding text.
 * @param rawScore Combined score (useful hits + severity bonus − not-useful hits).
 */
@Serializable
data class UsefulnessScore(
    val finding: Finding,
    val level: UsefulnessLevel,
    val usefulKeywordHits: Int,
    val notUsefulKeywordHits: Int,
    val rawScore: Int,
)

/** Usefulness classification for a code review finding. */
enum class UsefulnessLevel { USEFUL, UNCERTAIN, NOT_USEFUL }
