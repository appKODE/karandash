package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.validators.approval.ApprovalPolicy
import kotlinx.serialization.Serializable

/**
 * Configuration for the approval rule.
 *
 * Supports two modes:
 * 1. **Flat config**: set [minApprovals], [requiredApprovers],
 *    [noChangesSinceApproval], [noVeto], [noSelfApproval], [freshApprovals] directly.
 *    These are combined into an implicit [ApprovalPolicy.All].
 * 2. **Nested policy tree**: set [policy] to a composable [ApprovalPolicy] for
 *    advanced AND/OR/NOT composition.
 *
 * When [policy] is non-null it takes precedence; flat fields are ignored.
 */
@Serializable
data class ApprovalConfig(
    val minApprovals: Int = 1,
    val requiredApprovers: List<String> = emptyList(),
    val noChangesSinceApproval: Boolean = false,
    val noVeto: Boolean = false,
    val noSelfApproval: Boolean = false,
    val freshApprovals: Boolean = false,
    val policy: ApprovalPolicy? = null,
    /**
     * Which approvals count: [ApprovalMethod.CODE_REVIEW] (the platform's approve action), [ApprovalMethod.COMMENT_KEYWORD]
     * (a comment equal to one of [commentKeywords]) and [ApprovalMethod.EMOJI_REACTION] (a reaction named in [approvalEmojis]).
     * Written as `CODE_REVIEW`, `COMMENT_KEYWORD`, `EMOJI_REACTION`.
     */
    val methods: List<ApprovalMethod> = listOf(ApprovalMethod.CODE_REVIEW),
    val commentKeywords: List<String> = ApprovalSignal.DEFAULT_KEYWORDS,
    val approvalEmojis: List<String> = ApprovalSignal.DEFAULT_EMOJIS,
) : RuleConfig {
    init {
        require(minApprovals >= 0) {
            "minApprovals must be >= 0, got $minApprovals"
        }
    }

    /**
     * Resolves the effective policy: either the explicit [policy] tree,
     * or a synthesized [ApprovalPolicy.All] from the flat config fields.
     */
    fun resolvePolicy(): ApprovalPolicy {
        if (policy != null) return policy

        val policies = buildList {
            if (minApprovals > 0) {
                add(ApprovalPolicy.MinApprovals(count = minApprovals))
            }
            if (requiredApprovers.isNotEmpty()) {
                add(ApprovalPolicy.RequiredReviewers(usernames = requiredApprovers))
            }
            if (noVeto) {
                add(ApprovalPolicy.NoVeto)
            }
            if (noSelfApproval) {
                add(ApprovalPolicy.NoSelfApproval)
            }
            if (freshApprovals || noChangesSinceApproval) {
                add(ApprovalPolicy.FreshApprovals(count = 1))
            }
        }

        return when {
            policies.isEmpty() -> ApprovalPolicy.MinApprovals(count = 0)
            policies.size == 1 -> policies.first()
            else -> ApprovalPolicy.All(policies)
        }
    }
}
