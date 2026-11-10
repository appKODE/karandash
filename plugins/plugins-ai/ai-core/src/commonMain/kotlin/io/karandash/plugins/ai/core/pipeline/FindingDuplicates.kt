package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewThread
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.isAiFinding
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Cheap, LLM-free duplicate detection for AI file findings: token overlap of the texts, no extra calls.
 *
 * [againstThreads] drops a finding an open discussion by a person (or another tool) already covers, [inRun] drops
 * same-line repeats within one run. Threads the bot itself opened (their notes carry a finding marker) are left to
 * the reconciler of the platform output, which tracks them by marker; dropping the finding there would look like a fix.
 */
object FindingDuplicates {
    /** A thread covers a finding on a line at most this far from the thread's line. */
    const val LINE_WINDOW = 3

    /** Share of the shorter text's words that must occur in the other for the two to count as the same remark. */
    const val MIN_OVERLAP = 0.5

    private const val MIN_WORD_LENGTH = 3
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    private val WHITESPACE = Regex("\\s+")

    /** [findings] without the AI file findings that an unresolved, non-bot thread within [LINE_WINDOW] lines already says. */
    fun againstThreads(
        findings: List<Finding>,
        threads: List<ReviewThread>,
    ): List<Finding> {
        val open = threads.filter { isForeignOpenInline(it) }
        if (open.isEmpty()) return findings
        return findings.filter { finding ->
            val ref = lineRef(finding) ?: return@filter true
            open.none { thread ->
                val position = thread.position
                position?.newPath == ref.first &&
                    position.newLine?.let { abs(it - ref.second) <= LINE_WINDOW } == true &&
                    similar(finding.message, threadText(thread))
            }
        }
    }

    /**
     * [findings] with repeats of one AI file finding reduced to one, the most severe, then the most confident, the
     * first of equals; order is kept. Repeats are, in the same file and with similar text ([similar]): the same rule
     * on the same line, or (with [DedupConfig.crossRule]) another rule within [DedupConfig.lineWindow] lines, recorded
     * in [AiFindingMetadataKeys.MERGED_RULES]. Then same-file findings with the same message collapse into one that
     * lists their lines in [AiFindingMetadataKeys.MERGED_LINES]. A code suggestion merges only with its own rule's
     * same-line repeats: its code is its content, and it never merges with the review comment it pairs with.
     */
    fun inRun(
        findings: List<Finding>,
        dedup: DedupConfig = DedupConfig(),
    ): StageResult {
        val ranked = findings.indices
            .filter { lineRef(findings[it]) != null }
            .sortedWith(compareBy<Int> { findings[it].severity.ordinal }.thenByDescending { confidenceOf(findings[it]) })
        val merges = mutableMapOf<Int, Merge>()
        val kept = mutableListOf<Int>()
        for (i in ranked) {
            val match = kept.firstNotNullOfOrNull { k -> repeatReason(findings[k], findings[i], dedup)?.let { Merge(k, it) } }
            if (match == null) kept += i else merges[i] = match
        }
        kept
            .filter { findings[it].rule != AiRuleIds.CODE_SUGGESTIONS }
            .groupBy { lineRef(findings[it])?.first to normalized(findings[it].message) }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                group.drop(1).forEach {
                    merges[it] =
                        Merge(group.first(), "same message as line ${lineOf(findings[group.first()])}")
                }
            }
        val result = findings.indices.mapNotNull { i ->
            if (i in merges) null else withMerged(findings, i, merges.filterValues { finalKeeper(it.into, merges) == i }.keys)
        }
        val records = findings.indices.map { i ->
            val merge = merges[i]
            if (merge == null) {
                val count = merges.count { finalKeeper(it.value.into, merges) == i }
                StageRecord.of(findings[i], findings[i], StageRecord.KEEP, if (count > 0) "kept, merged $count" else "")
            } else {
                StageRecord.of(
                    findings[i],
                    null,
                    StageRecord.MERGE,
                    "into ${findings[merge.into].rule} line ${lineOf(findings[merge.into])}: ${merge.reason}"
                )
            }
        }
        return StageResult(result, records)
    }

    private fun repeatReason(
        keeper: Finding,
        finding: Finding,
        dedup: DedupConfig,
    ): String? {
        val a = lineRef(keeper) ?: return null
        val b = lineRef(finding) ?: return null
        if (a.first != b.first || !similar(keeper.message, finding.message)) return null
        val crossRule = keeper.rule != finding.rule && dedup.crossRule && AiRuleIds.CODE_SUGGESTIONS !in setOf(keeper.rule, finding.rule)
        return when {
            keeper.rule == finding.rule && a.second == b.second -> "same rule and line, similar text"
            crossRule && abs(a.second - b.second) <= dedup.lineWindow -> "other rule within ${dedup.lineWindow} lines, similar text"
            else -> null
        }
    }

    private fun withMerged(
        findings: List<Finding>,
        keeper: Int,
        merged: Set<Int>,
    ): Finding {
        val finding = findings[keeper]
        if (merged.isEmpty()) return finding
        val rules = merged.map { findings[it].rule }.filter { it != finding.rule }
        val lines = merged.mapNotNull { lineOf(findings[it]) }
        val extra = buildMap {
            if (rules.isNotEmpty()) put(AiFindingMetadataKeys.MERGED_RULES, (listOf(finding.rule) + rules).distinct().joinToString(","))
            val distinctLines = (listOfNotNull(lineRef(finding)?.second) + lines).distinct().sorted()
            if (distinctLines.size > 1) put(AiFindingMetadataKeys.MERGED_LINES, distinctLines.joinToString(","))
        }
        return finding.copy(metadata = finding.metadata + extra)
    }

    private fun finalKeeper(
        index: Int,
        merges: Map<Int, Merge>,
    ): Int = generateSequence(index) { merges[it]?.into }.last()

    private fun lineOf(finding: Finding): Int? = lineRef(finding)?.second

    private fun normalized(message: String): String =
        message
            .trim()
            .lowercase()
            .split(WHITESPACE)
            .joinToString(" ")

    /** Not @Serializable: a per-call intermediate of [inRun], never stored (its [StageRecord] is). */
    private data class Merge(
        val into: Int,
        val reason: String,
    )

    internal fun similar(
        a: String,
        b: String,
    ): Boolean {
        val wordsA = words(a)
        val wordsB = words(b)
        val smaller = minOf(wordsA.size, wordsB.size)
        return smaller > 0 && wordsA.intersect(wordsB).size.toDouble() / smaller >= MIN_OVERLAP
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(NON_WORD).filterTo(mutableSetOf()) { it.length >= MIN_WORD_LENGTH }

    private fun confidenceOf(finding: Finding): Double = finding.metadata[AiFindingMetadataKeys.CONFIDENCE]?.toDoubleOrNull() ?: 0.0

    private fun threadText(thread: ReviewThread): String = thread.notes.filter { !it.system }.joinToString(" ") { it.body }

    private fun isForeignOpenInline(thread: ReviewThread): Boolean =
        !thread.resolved &&
            thread.position?.newPath != null &&
            thread.notes.none { InlineFindingMarker.carriesFindings(it.body) }

    private fun lineRef(finding: Finding): Pair<String, Int>? {
        if (!finding.isAiFinding()) return null
        val ref = finding.location as? Location.FileRef ?: return null
        return ref.line?.let { ref.path to it }
    }
}

/**
 * `ai.dedup`: in-run duplicate merging, see [FindingDuplicates.inRun].
 *
 * @param crossRule Merge similar findings of different rules within [lineWindow] lines.
 * @param lineWindow How far apart two findings of different rules may be and still merge; not negative.
 */
@Serializable
data class DedupConfig(
    val crossRule: Boolean = true,
    val lineWindow: Int = FindingDuplicates.LINE_WINDOW,
) {
    /** This config, or a [ConfigParseException] naming the bad `ai.dedup` key. */
    fun validated(): DedupConfig {
        if (lineWindow < 0) throw ConfigParseException("ai.dedup.lineWindow: $lineWindow must not be negative")
        return this
    }
}
