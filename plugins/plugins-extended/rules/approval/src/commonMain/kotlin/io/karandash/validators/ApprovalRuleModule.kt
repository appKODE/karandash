@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.helpers.RuleIds
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class ApprovalRuleModule : AbstractTypedSingleRuleModule<ApprovalConfig>(ApprovalConfig.serializer()) {
    companion object {
        const val RULE_ID = RuleIds.APPROVAL
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Approval requirements ensure that merge requests are reviewed by a minimum number of qualified reviewers " +
            "before merging, maintaining code quality and knowledge sharing."

    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = setOf(MergeRequestField.APPROVALS)

    override val name: String = "Approval Rule"
    override val description: String = "Validates that MR has the required number of approvals."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: ApprovalConfig,
    ): List<Finding> = ApprovalValidator(config).validate(mr).map { it.withFixedText(ApprovalText.entries) }
}
