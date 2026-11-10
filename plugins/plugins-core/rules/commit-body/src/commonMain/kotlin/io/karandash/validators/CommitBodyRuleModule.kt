package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class CommitBodyRuleModule : AbstractTypedSingleRuleModule<CommitBodyConfig>(CommitBodyConfig.serializer()) {
    companion object {
        const val RULE_ID = "commitBody"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Commit bodies should follow a structured format so each commit can be traced and included in release notes."

    override val name: String = "Commit Body Rule"
    override val description: String = "Validates commit body presence and pattern matching."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CommitBodyConfig,
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CommitBodyConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = CommitBodyValidator(config, patterns).validate(mr).map { it.withFixedText(CommitBodyText.entries) }
}
