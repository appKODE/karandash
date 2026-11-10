package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class SizeLimitsRuleModule : AbstractTypedSingleRuleModule<SizeLimitsConfig>(SizeLimitsConfig.serializer()) {
    companion object {
        const val RULE_ID = "sizeLimits"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Size limits prevent overly large merge requests that are difficult to review effectively. Smaller, focused changes lead to better code reviews and fewer bugs."

    override val name: String = "Size Limits Rule"
    override val description: String = "Validates MR size limits (max files changed, lines added/removed, total commits)."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: SizeLimitsConfig
    ): List<Finding> = SizeLimitsValidator(config).validate(mr).map { it.withFixedText(SizeLimitsText.entries) }
}
