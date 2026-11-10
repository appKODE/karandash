package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.serialization.Serializable

/**
 * One finding's fate in a precision stage (grounding, ranking, dedup), dumped as `ai/stages/<stage>.json`.
 *
 * @param decision [KEEP], [DEMOTE], [DROP] or [MERGE].
 * @param reason Why, with the numbers the stage used; empty for a plain keep.
 */
@Serializable
data class StageRecord(
    val rule: String,
    val file: String?,
    val line: Int?,
    val category: String?,
    val severityBefore: String,
    val severityAfter: String?,
    val decision: String,
    val reason: String = "",
) {
    companion object {
        const val KEEP = "keep"
        const val DEMOTE = "demote"
        const val DROP = "drop"
        const val MERGE = "merge"

        /** The record of [before] becoming [after] (null when dropped or merged away). */
        fun of(
            before: Finding,
            after: Finding?,
            decision: String,
            reason: String = "",
        ): StageRecord {
            val ref = before.location as? Location.FileRef
            return StageRecord(
                rule = before.rule,
                file = ref?.path,
                line = ref?.line,
                category = before.metadata[AiFindingMetadataKeys.CATEGORY],
                severityBefore = before.severity.name,
                severityAfter = after?.severity?.name,
                decision = decision,
                reason = reason,
            )
        }
    }
}

/**
 * A precision stage's result.
 *
 * @param findings The kept, demoted and merged-into findings, in input order.
 * @param records One per input finding, dumped as `ai/stages/<stage>.json`.
 */
@Serializable
data class StageResult(
    val findings: List<Finding>,
    val records: List<StageRecord>,
)
