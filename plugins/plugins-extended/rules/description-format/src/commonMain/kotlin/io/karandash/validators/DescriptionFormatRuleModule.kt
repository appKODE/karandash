package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

open class DescriptionFormatRuleModule : AbstractTypedSingleRuleModule<DescriptionFormatConfig>(DescriptionFormatConfig.serializer()) {
    companion object {
        const val RULE_ID = "descriptionFormat"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "MR descriptions should follow a structured format with required sections (TASK, CHANGELOG, Closes, Proof)."

    override val name: String = "Description Format Rule"
    override val description: String = "Validates MR description structure."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: DescriptionFormatConfig,
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: DescriptionFormatConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = DescriptionFormatValidator(config, patterns).validate(mr).map { it.withFixedText(DescriptionFormatText.entries) }
}
