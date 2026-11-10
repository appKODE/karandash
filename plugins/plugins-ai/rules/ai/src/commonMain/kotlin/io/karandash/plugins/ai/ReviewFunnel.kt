package io.karandash.plugins.ai

import io.karandash.api.finding.FilterProvenance
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.dispatch.SampleAgreement

/**
 * The review-comments funnel as one INFO line: comments the samples left, how many they agreed on, how many
 * grounding confirmed, how many are posted, and the singletons kept as info and the findings grounding dropped.
 */
internal object ReviewFunnel {
    /** The funnel counts of the [before] and [after] post-processing findings by stage; empty without review comments. */
    fun counts(
        before: List<Finding>,
        after: List<Finding>,
        removed: List<FilterProvenance>,
    ): Map<String, Int> {
        val sampled = before.count { it.rule == AiRuleIds.REVIEW_COMMENTS }
        if (sampled == 0) return emptyMap()
        val kept = after.filter { it.rule == AiRuleIds.REVIEW_COMMENTS }
        val refuted = removed.filter { it.stage == GROUNDING_STAGE }.sumOf { it.removed } +
            kept.count { it.metadata[AiFindingMetadataKeys.GROUNDING] == REFUTED }
        val posted = kept.count {
            it.metadata[AiFindingMetadataKeys.AGREEMENT] != SampleAgreement.SINGLE &&
                it.metadata[AiFindingMetadataKeys.GROUNDING] != REFUTED
        }
        return linkedMapOf(
            SAMPLED to sampled,
            AGREED to kept.count { it.metadata[AiFindingMetadataKeys.AGREEMENT] == SampleAgreement.AGREED },
            GROUNDED to kept.count { it.metadata[AiFindingMetadataKeys.GROUNDING] == GROUNDED },
            POSTED to posted,
            SINGLETONS to kept.count { it.metadata[AiFindingMetadataKeys.AGREEMENT] == SampleAgreement.SINGLE },
            REFUTED_COUNT to refuted,
        )
    }

    /** The funnel [counts] as the INFO line; null without review comments. */
    fun line(counts: Map<String, Int>): String? {
        if (counts.isEmpty()) return null

        fun n(stage: String) = counts[stage] ?: 0
        return "AI review: ${n(SAMPLED)} comments → ${n(AGREED)} agreed → ${n(GROUNDED)} grounded → ${n(POSTED)} posted " +
            "(${n(SINGLETONS)} singletons as info, ${n(REFUTED_COUNT)} refuted by grounding)"
    }

    /** The funnel of the [before] and [after] post-processing findings as one line; null without review comments. */
    fun line(
        before: List<Finding>,
        after: List<Finding>,
        removed: List<FilterProvenance>,
    ): String? = line(counts(before, after, removed))

    const val SAMPLED = "sampled"
    const val AGREED = "agreed"
    const val POSTED = "posted"
    const val SINGLETONS = "singletons"
    const val REFUTED_COUNT = "refuted"

    private const val GROUNDING_STAGE = "grounding"

    // the grounding metadata values of ai-core's internal LocalGrounding; "grounded" is also the funnel stage name
    const val GROUNDED = "grounded"
    private const val REFUTED = "refuted"
}
