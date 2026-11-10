package io.karandash.validators.approval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Composable approval policy with AND/OR/NOT logic.
 *
 * Each variant evaluates against a list of reviews and the merge request
 * to produce a [PolicyResult] indicating whether the policy is satisfied.
 *
 * Policies can be nested arbitrarily:
 * ```yaml
 * policy:
 *   all:
 *     - minApprovals: 2
 *     - noVeto: {}
 *     - any:
 *       - requiredReviewers: { usernames: [alice] }
 *       - groupApproval: { group: backend, members: [bob, carol], minCount: 1 }
 * ```
 */
@Serializable
sealed class ApprovalPolicy {
    /** Require at least [count] approvals. */
    @Serializable
    @SerialName("minApprovals")
    data class MinApprovals(
        val count: Int = 1
    ) : ApprovalPolicy()

    /** Require approval from every listed username. */
    @Serializable
    @SerialName("requiredReviewers")
    data class RequiredReviewers(
        val usernames: List<String>
    ) : ApprovalPolicy()

    /** Require at least [minCount] approvals from [members] of a named [group]. */
    @Serializable
    @SerialName("groupApproval")
    data class GroupApproval(
        val group: String,
        val members: List<String>,
        val minCount: Int = 1,
    ) : ApprovalPolicy()

    /** ALL sub-policies must be satisfied. */
    @Serializable
    @SerialName("all")
    data class All(
        val policies: List<ApprovalPolicy>
    ) : ApprovalPolicy()

    /** At least one sub-policy must be satisfied. */
    @Serializable
    @SerialName("any")
    data class Any(
        val policies: List<ApprovalPolicy>
    ) : ApprovalPolicy()

    /** Negate a policy (useful for veto conditions). */
    @Serializable
    @SerialName("not")
    data class Not(
        val policy: ApprovalPolicy
    ) : ApprovalPolicy()

    /** No reviews with state CHANGES_REQUESTED. */
    @Serializable
    @SerialName("noVeto")
    data object NoVeto : ApprovalPolicy()

    /** The MR author must not be among the approvers. */
    @Serializable
    @SerialName("noSelfApproval")
    data object NoSelfApproval : ApprovalPolicy()

    /**
     * Require at least [count] approvals that occurred after the latest commit.
     *
     * Compares approval timestamps against the most recent commit timestamp
     * to detect stale approvals that predate new pushes.
     */
    @Serializable
    @SerialName("freshApprovals")
    data class FreshApprovals(
        val count: Int = 1
    ) : ApprovalPolicy()
}

/**
 * The result of evaluating an [ApprovalPolicy] against a merge request.
 *
 * @param satisfied Whether the policy condition is met.
 * @param reason A human-readable summary of the evaluation outcome.
 * @param details Optional list of specific sub-results or missing items.
 */
@Serializable
data class PolicyResult(
    val satisfied: Boolean,
    val reason: String,
    val details: List<String> = emptyList(),
)

/**
 * Convenience factory for building [PolicyResult] instances.
 */
internal object PolicyResults {
    fun pass(reason: String): PolicyResult = PolicyResult(satisfied = true, reason = reason)

    fun fail(
        reason: String,
        details: List<String> = emptyList()
    ): PolicyResult = PolicyResult(satisfied = false, reason = reason, details = details)
}
