package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class CrossEntityConsistencyRuleModule :
    AbstractTypedSingleRuleModule<CrossEntityConsistencyConfig>(CrossEntityConsistencyConfig.serializer()) {
    companion object {
        const val RULE_ID = "crossEntityConsistency"
    }

    override val defaultEnabled: Boolean = false
    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Ensures consistency between commit and MR task references. " +
            "Commit task IDs must be a subset of MR task IDs, and CHANGELOG entries must reference only known tasks."

    override val name: String = "Cross-Entity Consistency Rule"
    override val description: String = "Validates that commit task IDs and CHANGELOG entries are consistent with MR task references."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CrossEntityConsistencyConfig,
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CrossEntityConsistencyConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = CrossEntityConsistencyValidator(config, patterns).validate(mr).map { it.withFixedText(CrossEntityText.entries) }
}
