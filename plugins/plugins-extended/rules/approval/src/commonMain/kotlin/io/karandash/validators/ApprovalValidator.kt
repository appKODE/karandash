package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.utils.finding.FindingFactory
import io.karandash.validators.approval.ApprovalPolicy
import io.karandash.validators.approval.PolicyEvaluator
import io.karandash.validators.approval.PolicyResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Validates merge request approvals against a resolved [ApprovalPolicy].
 *
 * Delegates evaluation to [PolicyEvaluator] and converts unsatisfied
 * [PolicyResult] instances into [Finding] entries with structured
 * [FixSuggestion] data for auto-fix and reviewer assignment.
 */
class ApprovalValidator(
    private val config: ApprovalConfig,
) {
    internal companion object {
        val DEFAULT_SUGGESTION = ApprovalText.SUGGESTION.en
        val DEFAULT_EXPLANATION = ApprovalText.EXPLANATION.en
        const val REQUEST_REVIEW_FIX_TYPE = "request-review"
        const val REMOVE_REVIEW_FIX_TYPE = "remove-review"
    }

    /** Only approvals given by a configured method count; the default is the platform approve action alone. */
    fun validate(mr: MergeRequest): List<Finding> = evaluate(mr.copy(approvals = mr.approvals.filter { it.method in config.methods }))

    private fun evaluate(mr: MergeRequest): List<Finding> {
        val policy = config.resolvePolicy()
        val result = PolicyEvaluator.evaluate(policy, mr.approvals, mr)

        if (result.satisfied) return emptyList()

        return buildFindings(policy, result, mr)
    }

    private fun buildFindings(
        policy: ApprovalPolicy,
        result: PolicyResult,
        mr: MergeRequest,
    ): List<Finding> {
        // For composite policies, report each unsatisfied leaf individually
        if (policy is ApprovalPolicy.All) {
            return expandAllFindings(policy, mr)
        }

        val fixSuggestions = buildFixSuggestions(policy, mr)

        return listOf(
            FindingFactory.error(
                rule = ApprovalRuleModule.RULE_ID,
                message = result.reason,
                suggestion = DEFAULT_SUGGESTION,
                explanation = DEFAULT_EXPLANATION,
                fixSuggestions = fixSuggestions,
            )
        )
    }

    private fun expandAllFindings(
        policy: ApprovalPolicy.All,
        mr: MergeRequest,
    ): List<Finding> =
        policy.policies
            .map { sub -> sub to PolicyEvaluator.evaluate(sub, mr.approvals, mr) }
            .filter { (_, subResult) -> !subResult.satisfied }
            .map { (sub, subResult) ->
                FindingFactory.error(
                    rule = ApprovalRuleModule.RULE_ID,
                    message = subResult.reason,
                    suggestion = DEFAULT_SUGGESTION,
                    explanation = DEFAULT_EXPLANATION,
                    fixSuggestions = buildFixSuggestions(sub, mr),
                )
            }

    private fun buildFixSuggestions(
        policy: ApprovalPolicy,
        mr: MergeRequest,
    ): List<FixSuggestion> =
        when (policy) {
            is ApprovalPolicy.RequiredReviewers -> buildRequiredReviewerFixes(policy, mr)
            is ApprovalPolicy.GroupApproval -> buildGroupApprovalFixes(policy, mr)
            is ApprovalPolicy.MinApprovals -> buildMinApprovalFixes(mr)
            is ApprovalPolicy.NoSelfApproval -> buildSelfReviewFixes(mr)
            else -> emptyList()
        }

    private fun buildRequiredReviewerFixes(
        policy: ApprovalPolicy.RequiredReviewers,
        mr: MergeRequest,
    ): List<FixSuggestion> {
        val approvedUsernames = mr.approvals
            .filter { it.state == ReviewState.APPROVED }
            .map { it.user.username }
            .toSet()
        return policy.usernames
            .filter { it !in approvedUsernames }
            .map { username -> requestReviewFix(username) }
    }

    private fun buildGroupApprovalFixes(
        policy: ApprovalPolicy.GroupApproval,
        mr: MergeRequest,
    ): List<FixSuggestion> {
        val approvedUsernames = mr.approvals
            .filter { it.state == ReviewState.APPROVED }
            .map { it.user.username }
            .toSet()
        return policy.members
            .filter { it !in approvedUsernames }
            .map { username -> requestReviewFix(username) }
    }

    private fun buildMinApprovalFixes(mr: MergeRequest,): List<FixSuggestion> {
        // Suggest requesting review from assigned reviewers who haven't approved
        val approvedUsernames = mr.approvals
            .filter { it.state == ReviewState.APPROVED }
            .map { it.user.username }
            .toSet()
        return mr.reviewers
            .filter { it.username !in approvedUsernames }
            .map { reviewer -> requestReviewFix(reviewer.username) }
    }

    /** a self-approving author who is also a reviewer is taken off the reviewer list. */
    private fun buildSelfReviewFixes(mr: MergeRequest): List<FixSuggestion> =
        mr.reviewers
            .filter { it.username == mr.author.username }
            .map { reviewer ->
                FixSuggestion.Custom(
                    type = REMOVE_REVIEW_FIX_TYPE,
                    data = buildJsonObject { put("username", reviewer.username) },
                    description = "Remove the MR author ${reviewer.username} from reviewers",
                )
            }

    private fun requestReviewFix(username: String): FixSuggestion.Custom =
        FixSuggestion.Custom(
            type = REQUEST_REVIEW_FIX_TYPE,
            data = buildJsonObject { put("username", username) },
            description = "Request review from $username",
        )
}
