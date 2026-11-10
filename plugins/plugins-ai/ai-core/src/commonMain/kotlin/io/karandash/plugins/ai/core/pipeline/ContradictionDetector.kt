package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.utils.finding.toFilePosition
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Detects contradictory AI findings on the same file within a proximity window.
 *
 * When two AI findings on the same file are within [LINE_PROXIMITY_THRESHOLD] lines of each other
 * and their messages contain opposing keywords (e.g. "add" vs "remove"), the finding with the
 * lower confidence metadata is removed (with `resolution: keep-higher-severity`, the lower severity
 * one, confidence breaking ties). If both are equal or absent, the second finding (by list order)
 * is removed.
 *
 * Only AI findings ([isAiFinding]) are subject to contradiction detection.
 * Non-AI findings pass through unchanged.
 */
class ContradictionDetector(
    private val resolution: String = ContradictionsConfig.KEEP_HIGHER_CONFIDENCE,
) {
    /**
     * Scans the given findings for contradictions and removes the lower-confidence member of
     * each contradictory pair.
     *
     * @param findings All validation findings (AI and non-AI).
     * @return A [ContradictionResult] containing the filtered findings and the count of
     *   contradictions found.
     */
    fun detect(findings: List<Finding>): ContradictionResult {
        val aiFindings = findings.filter { it.isAiFinding() }
        if (aiFindings.size < MIN_AI_FINDINGS_FOR_DETECTION) {
            return ContradictionResult(findings = findings, contradictionsFound = 0)
        }

        val toRemove = mutableSetOf<Finding>()
        val reasons = mutableListOf<String>()

        val byFile = aiFindings.groupBy { filePathOf(it) }
        for ((_, group) in byFile) {
            if (group.size < 2) continue
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val a = group[i]
                    val b = group[j]
                    if (a in toRemove || b in toRemove) continue
                    if (!areNearby(a, b)) continue
                    if (!areContradictory(a, b)) continue
                    val loser = loserOf(a, b)
                    val winner = if (loser === a) b else a
                    toRemove.add(loser)
                    reasons += "kept ${describe(winner)} over ${describe(loser)} ($resolution)"
                }
            }
        }

        return ContradictionResult(
            findings = findings.filter { it !in toRemove },
            contradictionsFound = toRemove.size,
            reasons = reasons,
        )
    }

    private fun loserOf(
        a: Finding,
        b: Finding,
    ): Finding {
        if (resolution == ContradictionsConfig.KEEP_HIGHER_SEVERITY) {
            val bySeverity = severityRank(a.severity) - severityRank(b.severity)
            if (bySeverity != 0) return if (bySeverity < 0) a else b
        }
        return lowerConfidence(a, b)
    }

    companion object {
        /** Minimum number of AI findings required before contradiction detection runs. */
        const val MIN_AI_FINDINGS_FOR_DETECTION = 2

        /**
         * Maximum line distance between two findings on the same file for them to be
         * considered as potentially contradictory.
         */
        const val LINE_PROXIMITY_THRESHOLD = 5

        /**
         * Pairs of opposing keywords. If a finding message contains the first keyword and
         * another contains the second (or vice versa), they are treated as contradictory.
         */
        private val OPPOSING_PAIRS: List<Pair<String, String>> = listOf(
            "add" to "remove",
            "increase" to "decrease",
            "split" to "merge",
            "simplify" to "expand",
            "extract" to "inline",
            "missing" to "unnecessary",
            "too long" to "too short",
            "too complex" to "too simple",
        )

        // the shared Location.toFilePosition() reads the file position.
        private fun filePathOf(finding: Finding): String =
            finding.location
                ?.toFilePosition()
                ?.first
                .orEmpty()

        private fun lineOf(finding: Finding): Int? = finding.location?.toFilePosition()?.second

        private fun areNearby(
            a: Finding,
            b: Finding
        ): Boolean {
            val lineA = lineOf(a) ?: return true
            val lineB = lineOf(b) ?: return true
            return abs(lineA - lineB) <= LINE_PROXIMITY_THRESHOLD
        }

        private fun areContradictory(
            a: Finding,
            b: Finding
        ): Boolean {
            if (a.rule == b.rule) return false
            val msgA = a.message.lowercase()
            val msgB = b.message.lowercase()
            return OPPOSING_PAIRS.any { (first, second) ->
                (msgA.contains(first) && msgB.contains(second)) ||
                    (msgA.contains(second) && msgB.contains(first))
            }
        }

        private fun describe(finding: Finding): String =
            "[${finding.rule}] ${finding.severity} " +
                "confidence=${confidenceOf(finding) ?: "n/a"} '${finding.message.take(DESCRIBE_MESSAGE_CHARS)}'"

        private const val DESCRIBE_MESSAGE_CHARS = 80

        private fun confidenceOf(finding: Finding): Double? = finding.metadata[AiFindingMetadataKeys.CONFIDENCE]?.toDoubleOrNull()

        /**
         * Returns the finding with the lower confidence. When both have equal confidence
         * or neither has confidence metadata, returns [b].
         */
        private fun lowerConfidence(
            a: Finding,
            b: Finding
        ): Finding {
            val confA = confidenceOf(a)
            val confB = confidenceOf(b)
            return when {
                confA != null && confB != null -> if (confA <= confB) a else b
                confA != null -> b
                confB != null -> a
                else -> b
            }
        }
    }
}

/**
 * Result of contradiction detection.
 *
 * @param findings The findings list with contradictory lower-confidence entries removed.
 * @param contradictionsFound Number of findings removed as contradictions.
 * @param reasons One line per removal naming the kept and the removed finding.
 */
@Serializable
data class ContradictionResult(
    val findings: List<Finding>,
    val contradictionsFound: Int,
    val reasons: List<String> = emptyList(),
)

/**
 * `ai.contradictions` block: `enabled: false` keeps contradictory AI findings; [resolution] picks
 * the survivor of a pair ([KEEP_HIGHER_CONFIDENCE], the default, or [KEEP_HIGHER_SEVERITY]).
 */
@Serializable
data class ContradictionsConfig(
    val enabled: Boolean = true,
    val resolution: String = KEEP_HIGHER_CONFIDENCE,
) {
    companion object {
        const val KEEP_HIGHER_CONFIDENCE = "keep-higher-confidence"
        const val KEEP_HIGHER_SEVERITY = "keep-higher-severity"
    }
}
