package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class LanguagePolicyRuleModule : AbstractTypedSingleRuleModule<LanguagePolicyConfig>(LanguagePolicyConfig.serializer()) {
    companion object {
        const val RULE_ID = "languagePolicy"
    }

    override val defaultEnabled: Boolean = false
    override val ruleId: String = RULE_ID
    override val explanation: String =
        "All Git entities (branch names, commit titles, commit descriptions, MR titles, MR descriptions) " +
            "must be written in English using Latin script characters."

    override val name: String = "Language Policy Rule"
    override val description: String = "Enforces that all text content is written in English (Latin script only)."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: LanguagePolicyConfig,
    ): List<Finding> = LanguagePolicyValidator(config).validate(mr).map { it.withFixedText(LanguagePolicyText.entries) }
}
