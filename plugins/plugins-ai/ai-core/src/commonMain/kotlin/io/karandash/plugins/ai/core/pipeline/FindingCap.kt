package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import kotlinx.serialization.Serializable

/**
 * The one cap rule for AI review output, ranked by score: [RankingWeights.agreed] when a second sample agreed
 * ([AiFindingMetadataKeys.AGREEMENT] `agreed`), [RankingWeights.grounded] when local grounding found the claim
 * ([AiFindingMetadataKeys.GROUNDING] `grounded`), [RankingWeights.security] for a SEC or DATA finding; ties go to
 * the more severe, then the more confident finding, then the earlier one.
 *
 * A BUG/SEC/DATA/LEAK finding ([PROTECTED_CATEGORIES]) that meets every [RankingConfig.protect] condition is never cut
 * by `max`, only by `hardMax`. A condition whose metadata key no finding of the list carries is neutral (met): the
 * dispatch-time review-comments cap runs before grounding, and agreement exists only with several samples.
 *
 * Below the cut a [PROTECTED_CATEGORIES] finding becomes INFO with a `below-cap rank N score S` reason, so a possible
 * bug is never dropped by a cap; any other finding is dropped. A code suggestion paired with a review comment of the
 * list ([AiMetadataTransformer.pairsWith]) is not counted and shares its comment's fate. A single-sample note
 * (`agreement=single`) is kept and not counted. Order is kept.
 */
object FindingCap {
    /** Review-comments categories a cap never drops: wrong results, security, data loss, leaks. */
    val PROTECTED_CATEGORIES = setOf("BUG", "SEC", "DATA", "LEAK")

    private val SECURITY_CATEGORIES = setOf("SEC", "DATA")

    /** [AiFindingMetadataKeys.AGREEMENT] of a comment only one review-comments sample reported. */
    private const val SINGLE = "single"

    /** A finding without a confidence score counts as a coin flip. */
    private const val UNSCORED_CONFIDENCE = 0.5

    /** True for a [PROTECTED_CATEGORIES] finding, whatever its ranking. */
    fun isProtected(finding: Finding): Boolean = finding.metadata[AiFindingMetadataKeys.CATEGORY] in PROTECTED_CATEGORIES

    /** [findings] cut to [max] (a protected finding past it, never past [hardMax]), with one record per finding. */
    fun keep(
        findings: List<Finding>,
        max: Int,
        hardMax: Int? = null,
        ranking: RankingConfig = RankingConfig(),
    ): StageResult {
        val parents = findings.filter { it.rule == AiRuleIds.REVIEW_COMMENTS && it.location != null }
        val twinOf = findings
            .filter { it.rule == AiRuleIds.CODE_SUGGESTIONS }
            .mapNotNull { s -> parents.firstOrNull { AiMetadataTransformer.pairsWith(s, it) }?.let { s to it } }
            .toMap()
        // a single-sample note (`agreement=single`, already INFO and file-level) takes no cap slot
        val singles = findings.filter { it.metadata[AiFindingMetadataKeys.AGREEMENT] == SINGLE }.toSet()
        val counted = findings.filter { it !in twinOf && it !in singles }
        val neutral = ranking.protect.filter { key -> counted.none { conditionKey(key) in it.metadata } }.toSet()
        val eligible = counted.filter { isProtected(it) && ranking.protect.all { key -> key in neutral || meets(it, key) } }.toSet()
        val ranked = counted.sortedWith(
            compareBy<Finding> { if (it in eligible) 0 else 1 }
                .thenByDescending { score(it, ranking.weights) }
                .thenBy { it.severity.ordinal }
                .thenByDescending { it.metadata[AiFindingMetadataKeys.CONFIDENCE]?.toDoubleOrNull() ?: UNSCORED_CONFIDENCE },
        )
        val limit = maxOf(max, eligible.size).let { if (hardMax != null) minOf(it, hardMax) else it }
        val outcome = ranked.mapIndexed { i, f -> f to cut(f, i, limit, score(f, ranking.weights)) }.toMap() +
            singles.associateWith { Outcome(it, StageRecord.KEEP, "single sample, no cap slot") }
        val after = findings.map { f -> twinOf[f]?.let { follow(f, outcome.getValue(it)) } ?: outcome.getValue(f) }
        return StageResult(
            findings = after.mapNotNull { it.finding },
            records = findings.zip(after).map { (f, o) -> StageRecord.of(f, o.finding, o.decision, o.reason) },
        )
    }

    private fun cut(
        finding: Finding,
        index: Int,
        limit: Int,
        score: Int,
    ): Outcome {
        val reason = "rank ${index + 1} score $score"
        val below = "below-cap $reason"
        if (index < limit) return Outcome(finding, StageRecord.KEEP, "$reason within cap $limit")
        if (!isProtected(finding)) return Outcome(null, StageRecord.DROP, below)
        val demoted = finding.copy(
            severity = Severity.INFO,
            metadata = finding.metadata +
                (AiFindingMetadataKeys.DECISION_REASON to below) +
                (AiFindingMetadataKeys.SCORE to score.toString()),
        )
        return Outcome(demoted, StageRecord.DEMOTE, below)
    }

    private fun follow(
        twin: Finding,
        parent: Outcome,
    ): Outcome {
        val why = "review comment ${parent.reason}"
        return when (parent.decision) {
            StageRecord.DROP -> Outcome(null, StageRecord.DROP, why)
            StageRecord.DEMOTE -> Outcome(twin.copy(severity = Severity.INFO), StageRecord.DEMOTE, why)
            else -> Outcome(twin, StageRecord.KEEP, why)
        }
    }

    private fun score(
        finding: Finding,
        weights: RankingWeights,
    ): Int {
        val agreed = if (meets(finding, RankingConfig.AGREED)) weights.agreed else 0
        val grounded = if (meets(finding, RankingConfig.GROUNDED)) weights.grounded else 0
        val security = if (finding.metadata[AiFindingMetadataKeys.CATEGORY] in SECURITY_CATEGORIES) weights.security else 0
        return agreed + grounded + security
    }

    private fun meets(
        finding: Finding,
        condition: String,
    ): Boolean = finding.metadata[conditionKey(condition)] == condition

    private fun conditionKey(condition: String): String =
        if (condition == RankingConfig.AGREED) AiFindingMetadataKeys.AGREEMENT else AiFindingMetadataKeys.GROUNDING

    /** Not @Serializable: a per-call intermediate, never stored (its [StageRecord] is). */
    private data class Outcome(
        val finding: Finding?,
        val decision: String,
        val reason: String,
    )
}

/**
 * `ai.ranking`: how [FindingCap] ranks findings when a cap has to cut.
 *
 * @param weights Score per signal, see [RankingWeights].
 * @param protect Conditions ([AGREED], [GROUNDED]) a BUG/SEC/DATA/LEAK finding must all meet to be kept past the cap.
 */
@Serializable
data class RankingConfig(
    val weights: RankingWeights = RankingWeights(),
    val protect: List<String> = listOf(AGREED, GROUNDED),
) {
    /** This config, or a [ConfigParseException] naming the bad `ai.ranking` key. */
    fun validated(): RankingConfig {
        val unknown = protect.filterNot { it in setOf(AGREED, GROUNDED) }
        if (unknown.isNotEmpty()) throw ConfigParseException("ai.ranking.protect: $unknown are not $AGREED or $GROUNDED")
        if (listOf(weights.agreed, weights.grounded, weights.security).any { it < 0 }) {
            throw ConfigParseException("ai.ranking.weights: weights must not be negative ($weights)")
        }
        return this
    }

    companion object {
        const val AGREED = "agreed"
        const val GROUNDED = "grounded"
    }
}

/** `ai.ranking.weights`: score of a sample-agreed, a grounded and a SEC/DATA finding. */
@Serializable
data class RankingWeights(
    val agreed: Int = 2,
    val grounded: Int = 1,
    val security: Int = 1,
)
