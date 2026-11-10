package io.karandash.api.platform

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.serialization.Serializable

/**
 * A [io.karandash.models.MergeRequest] field that some platforms cannot fill.
 *
 * Rules declare the fields they need via [io.karandash.api.module.RuleModule.requiredMergeRequestFields];
 * platforms declare the fields they never fill via
 * [io.karandash.api.module.PlatformServiceModule.unavailableMergeRequestFields]. The engine skips such a rule
 * with a warning naming the platform and the field, instead of letting it pass on an empty value.
 */
@ExperimentalKarandashApi
@Serializable
public enum class MergeRequestField(
    public val displayName: String,
) {
    /** `MergeRequest.ciStatus`: overall pipeline / status-check state. */
    CI_STATUS("CI status"),

    /** `MergeRequest.hasConflicts` / `MergeRequest.mergeStatus`: merge-conflict state. */
    CONFLICTS("merge-conflict status"),

    /** `MergeRequest.approvals`. */
    APPROVALS("approvals"),

    /** `MergeRequest.reviewers`. */
    REVIEWERS("reviewers"),

    /** `MergeRequest.labels`. */
    LABELS("labels"),

    /** `MergeRequest.ciJobs`: the individual CI jobs / checks behind [CI_STATUS]. */
    CI_JOBS("CI jobs"),
}
