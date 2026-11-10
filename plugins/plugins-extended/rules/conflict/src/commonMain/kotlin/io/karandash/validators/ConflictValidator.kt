package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.config.BlockMode
import io.karandash.utils.finding.FindingFactory

class ConflictValidator(
    private val config: ConflictConfig
) {
    fun validate(mr: MergeRequest): List<Finding> {
        if (config.blockOnConflicts == BlockMode.OFF) {
            return emptyList()
        }

        val hasConflicts = mr.hasConflicts == true ||
            isMergeStatusConflicted(mr.mergeStatus)

        return buildList {
            if (hasConflicts) {
                add(
                    FindingFactory
                        .error(
                            rule = ConflictRuleModule.RULE_ID,
                            message = "MR has merge conflicts that must be resolved before merging",
                            suggestion = "Rebase or merge the target branch to resolve conflicts.",
                            explanation = "Merge conflicts must be resolved before merging."
                        ).let { checkNotNull(config.blockOnConflicts.applyTo(it)) }
                )
            }
        }
    }

    private fun isMergeStatusConflicted(status: String?): Boolean {
        val normalized = status?.trim()?.lowercase() ?: return false
        return normalized in CONFLICT_STATUSES
    }

    companion object {
        private val CONFLICT_STATUSES = setOf(
            "cannot_be_merged",
            "cannot-be-merged",
            "dirty",
            "conflict",
            "conflicts",
            "conflicting",
            "has_conflicts",
            "unmergeable",
        )
    }
}
