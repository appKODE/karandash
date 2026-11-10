package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

open class CommitMessagesRuleModule : AbstractTypedSingleRuleModule<CommitMessagesConfig>(CommitMessagesConfig.serializer()) {
    companion object {
        const val RULE_ID = "commitMessages"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Commit messages should be clear and descriptive, following conventional commit format when configured. " +
            "Good commit messages provide a history of why changes were made."

    override val name: String = "Commit Messages Rule"
    override val description: String = "Validates commit messages for format, length, and conventional commit patterns."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CommitMessagesConfig,
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CommitMessagesConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = CommitMessagesValidator(config, patterns).validate(mr).map { it.withFixedText(CommitMessagesText.entries) }
}
