package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

open class SecurityRuleModule : AbstractTypedSingleRuleModule<SecurityConfig>(SecurityConfig.serializer()) {
    companion object {
        const val RULE_ID = "security"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Security checks scan for vulnerabilities, exposed secrets, or insecure coding practices to protect " +
            "the codebase from security threats."

    override val name: String = "Security Rule"
    override val description: String = "Scans MR diff for secrets, sensitive files, and security patterns."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: SecurityConfig
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: SecurityConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = SecurityValidator(config, patterns).validate(mr).map { it.withFixedText(SecurityText.entries) }
}
