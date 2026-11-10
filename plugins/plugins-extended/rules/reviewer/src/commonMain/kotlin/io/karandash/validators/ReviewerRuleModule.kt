@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class ReviewerRuleModule : AbstractTypedSingleRuleModule<ReviewerConfig>(ReviewerConfig.serializer()) {
    companion object {
        const val RULE_ID = "reviewer"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Reviewer requirements ensure that specific individuals or teams review changes, leveraging domain " +
            "expertise and maintaining accountability."

    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = setOf(MergeRequestField.REVIEWERS)

    override val name: String = "Reviewer Rule"
    override val description: String = "Validates reviewer assignment rules."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: ReviewerConfig
    ): List<Finding> = ReviewerValidator(config).validate(mr).map { it.withFixedText(ReviewerText.entries) }
}
