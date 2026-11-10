package io.karandash.eval

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import kotlinx.serialization.Serializable

/**
 * Tolerant matcher + P/R scorer.
 *
 * A finding matches a label when the analyzer is the same and:
 * - label `file` set: the finding is a [Location.FileRef] on the same path; when both sides carry
 *   lines, the finding's line range overlaps the label's range widened by [LINE_WINDOW];
 * - label `category` set: some metadata value (or comma-separated item of one) equals it, ignoring case.
 *
 * Labels are matched greedily one-to-one. A finding that matches only an already-taken label is a
 * duplicate (counted neither way); any other unmatched finding is a false positive and every
 * unmatched label a false negative.
 */
object EvalScorer {
    const val LINE_WINDOW = 3

    @Serializable
    data class Outcome(
        val score: EvalScore,
        val misses: List<String>,
    )

    fun score(
        analyzer: String,
        findings: List<Finding>,
        labels: List<EvalLabel>,
    ): Outcome {
        val open = labels.filter { it.analyzer == analyzer }.toMutableList()
        val taken = mutableListOf<EvalLabel>()
        val misses = mutableListOf<String>()
        var anchorable = 0
        var anchored = 0
        val verdicts = findings.map { finding ->
            val hit = open.firstOrNull { matches(finding, it) }
            when {
                hit != null -> {
                    open.remove(hit)
                    taken += hit
                    exactLine(finding, hit)?.let {
                        anchorable++
                        if (it) anchored++
                    }
                    Verdict.HIT
                }

                taken.any { matches(finding, it) } -> {
                    Verdict.DUPLICATE
                }

                else -> {
                    misses += "FP $analyzer: ${describe(finding)}"
                    Verdict.FALSE_POSITIVE
                }
            }
        }
        open.forEach { misses += "FN $analyzer: $it" }
        return Outcome(
            EvalScore(
                tp = verdicts.count { it == Verdict.HIT },
                fp = verdicts.count { it == Verdict.FALSE_POSITIVE },
                fn = open.size,
                duplicates = verdicts.count { it == Verdict.DUPLICATE },
                anchorable = anchorable,
                anchored = anchored,
            ),
            misses,
        )
    }

    /** Null when label or finding has no line; else whether the finding starts inside the label range, no window. */
    private fun exactLine(
        finding: Finding,
        label: EvalLabel,
    ): Boolean? {
        val start = label.startLine ?: return null
        val line = (finding.location as? Location.FileRef)?.line ?: return null
        return line in start..(label.endLine ?: start)
    }

    fun matches(
        finding: Finding,
        label: EvalLabel,
    ): Boolean = fileMatches(finding.location, label) && categoryMatches(finding, label.category)

    private fun fileMatches(
        location: Location?,
        label: EvalLabel,
    ): Boolean {
        val file = label.file ?: return true
        val ref = location as? Location.FileRef ?: return false
        if (normalize(ref.path) != normalize(file)) return false
        val start = label.startLine ?: return true
        val line = ref.line ?: return true
        val end = label.endLine ?: start
        val findingEnd = ref.endLine ?: line
        return line <= end + LINE_WINDOW && findingEnd >= start - LINE_WINDOW
    }

    private fun categoryMatches(
        finding: Finding,
        category: String?,
    ): Boolean {
        if (category == null) return true
        return finding.metadata.values.any { value ->
            value.split(',', ';').any { it.trim().equals(category, ignoreCase = true) }
        }
    }

    private fun normalize(path: String): String = path.trim().removePrefix("./").removePrefix("/")

    private fun describe(finding: Finding): String {
        val where = (finding.location as? Location.FileRef)?.let { " @${it.path}:${it.line}" }.orEmpty()
        return "[${finding.severity}]$where ${finding.message.take(MESSAGE_PREVIEW)}"
    }

    private const val MESSAGE_PREVIEW = 80

    private enum class Verdict { HIT, DUPLICATE, FALSE_POSITIVE }
}
