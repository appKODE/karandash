package io.karandash.validators.approval

import io.karandash.api.support.suspendCatching
import io.karandash.models.Approval
import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import kotlin.time.Instant

/**
 * Evaluates an [ApprovalPolicy] tree against a merge request's reviews.
 *
 * Stateless - create or reuse freely. Each [evaluate] call is self-contained.
 */
internal object PolicyEvaluator {
    fun evaluate(
        policy: ApprovalPolicy,
        reviews: List<Approval>,
        mr: MergeRequest,
    ): PolicyResult =
        when (policy) {
            is ApprovalPolicy.MinApprovals -> evaluateMinApprovals(policy, reviews)
            is ApprovalPolicy.RequiredReviewers -> evaluateRequiredReviewers(policy, reviews)
            is ApprovalPolicy.GroupApproval -> evaluateGroupApproval(policy, reviews)
            is ApprovalPolicy.All -> evaluateAll(policy, reviews, mr)
            is ApprovalPolicy.Any -> evaluateAny(policy, reviews, mr)
            is ApprovalPolicy.Not -> evaluateNot(policy, reviews, mr)
            is ApprovalPolicy.NoVeto -> evaluateNoVeto(reviews)
            is ApprovalPolicy.NoSelfApproval -> evaluateNoSelfApproval(reviews, mr)
            is ApprovalPolicy.FreshApprovals -> evaluateFreshApprovals(policy, reviews, mr)
        }

    private fun evaluateMinApprovals(
        policy: ApprovalPolicy.MinApprovals,
        reviews: List<Approval>,
    ): PolicyResult {
        val approvedCount = reviews.count { it.state == ReviewState.APPROVED }
        return if (approvedCount >= policy.count) {
            PolicyResults.pass("$approvedCount/${policy.count} approvals present")
        } else {
            PolicyResults.fail(
                reason = "Insufficient approvals: $approvedCount of ${policy.count} required",
            )
        }
    }

    private fun evaluateRequiredReviewers(
        policy: ApprovalPolicy.RequiredReviewers,
        reviews: List<Approval>,
    ): PolicyResult {
        val approvedUsernames = reviews
            .filter { it.state == ReviewState.APPROVED }
            .map { it.user.username }
            .toSet()
        val missing = policy.usernames.filter { it !in approvedUsernames }
        return if (missing.isEmpty()) {
            PolicyResults.pass("All required reviewers have approved")
        } else {
            PolicyResults.fail(
                reason = "Missing required approvers: ${missing.joinToString(", ")}",
                details = missing,
            )
        }
    }

    private fun evaluateGroupApproval(
        policy: ApprovalPolicy.GroupApproval,
        reviews: List<Approval>,
    ): PolicyResult {
        val memberSet = policy.members.toSet()
        val groupApprovals = reviews.count {
            it.state == ReviewState.APPROVED && it.user.username in memberSet
        }
        return if (groupApprovals >= policy.minCount) {
            PolicyResults.pass(
                "$groupApprovals/${policy.minCount} approvals from group '${policy.group}'",
            )
        } else {
            PolicyResults.fail(
                reason = "Insufficient approvals from group '${policy.group}': " +
                    "$groupApprovals of ${policy.minCount} required",
            )
        }
    }

    private fun evaluateAll(
        policy: ApprovalPolicy.All,
        reviews: List<Approval>,
        mr: MergeRequest,
    ): PolicyResult {
        val results = policy.policies.map { evaluate(it, reviews, mr) }
        val failures = results.filter { !it.satisfied }
        return if (failures.isEmpty()) {
            PolicyResults.pass("All ${results.size} policies satisfied")
        } else {
            PolicyResults.fail(
                reason = "${failures.size} of ${results.size} policies not satisfied",
                details = failures.map { it.reason },
            )
        }
    }

    private fun evaluateAny(
        policy: ApprovalPolicy.Any,
        reviews: List<Approval>,
        mr: MergeRequest,
    ): PolicyResult {
        val results = policy.policies.map { evaluate(it, reviews, mr) }
        val passes = results.filter { it.satisfied }
        return if (passes.isNotEmpty()) {
            PolicyResults.pass("${passes.size} of ${results.size} policies satisfied")
        } else {
            PolicyResults.fail(
                reason = "None of ${results.size} policies satisfied",
                details = results.map { it.reason },
            )
        }
    }

    private fun evaluateNot(
        policy: ApprovalPolicy.Not,
        reviews: List<Approval>,
        mr: MergeRequest,
    ): PolicyResult {
        val inner = evaluate(policy.policy, reviews, mr)
        return if (inner.satisfied) {
            PolicyResults.fail(reason = "Negated policy was satisfied: ${inner.reason}")
        } else {
            PolicyResults.pass("Negated policy correctly unsatisfied")
        }
    }

    private fun evaluateNoVeto(reviews: List<Approval>): PolicyResult {
        val vetoes = reviews.filter { it.state == ReviewState.CHANGES_REQUESTED }
        return if (vetoes.isEmpty()) {
            PolicyResults.pass("No changes-requested reviews")
        } else {
            val vetoers = vetoes.map { it.user.username }
            PolicyResults.fail(
                reason = "Changes requested by: ${vetoers.joinToString(", ")}",
                details = vetoers,
            )
        }
    }

    private fun evaluateNoSelfApproval(
        reviews: List<Approval>,
        mr: MergeRequest,
    ): PolicyResult {
        val selfApprovals = reviews.filter {
            it.user.username == mr.author.username && it.state == ReviewState.APPROVED
        }
        return if (selfApprovals.isEmpty()) {
            PolicyResults.pass("No self-approvals detected")
        } else {
            PolicyResults.fail(
                reason = "Self-approval detected from MR author '${mr.author.username}'",
            )
        }
    }

    private fun evaluateFreshApprovals(
        policy: ApprovalPolicy.FreshApprovals,
        reviews: List<Approval>,
        mr: MergeRequest,
    ): PolicyResult {
        val latestCommitInstant = mr.commits
            .asSequence()
            .mapNotNull { parseInstantOrNull(it.timestamp) }
            .maxOrNull()
            ?: return PolicyResults.pass("No commits to compare against")

        val freshCount = reviews.count { review ->
            review.state == ReviewState.APPROVED &&
                review.approvedAt?.let(::parseInstantOrNull)?.let { it > latestCommitInstant } == true
        }

        return if (freshCount >= policy.count) {
            PolicyResults.pass("$freshCount/${policy.count} fresh approvals after latest commit")
        } else {
            PolicyResults.fail(
                reason = "Insufficient fresh approvals: $freshCount of ${policy.count} required " +
                    "(approvals must be after the latest commit)",
            )
        }
    }

    private fun parseInstantOrNull(value: String): Instant? = suspendCatching { Instant.parse(value) }.getOrNull()
}
