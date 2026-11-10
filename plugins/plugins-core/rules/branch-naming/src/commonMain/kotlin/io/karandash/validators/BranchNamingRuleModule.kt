package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.patterns.PatternDefinition
import io.karandash.patterns.PatternParam
import io.karandash.patterns.PatternRegistry
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import io.karandash.utils.parseCsvList
import io.karandash.utils.toEscapedAlternation

class BranchNamingRuleModule : AbstractTypedSingleRuleModule<BranchNamingConfig>(BranchNamingConfig.serializer()) {
    companion object {
        const val RULE_ID = "branchNaming"
        private const val PARAM_PREFIXES = "prefixes"
        private const val DEFAULT_PREFIXES_VALUE = "feature/,bugfix/,hotfix/"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Branch names should follow a naming convention (e.g., feature/, bugfix/, hotfix/) to help organize work " +
            "and understand the purpose of branches at a glance."

    override val name: String = "Branch Naming Rule"
    override val description: String = "Validates branch naming conventions using regex patterns."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: BranchNamingConfig,
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: BranchNamingConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = BranchNamingValidator(config, patterns).validate(mr).map { it.withFixedText(BranchNamingText.entries) }

    override fun registerPatterns(registry: PatternRegistry) {
        registry.register(
            PatternDefinition(
                name = "branch-prefix",
                description = "Branch name starts with one of the given prefixes. " +
                    "Use @prefixes:feature/,bugfix/ to configure allowed prefixes.",
                params = listOf(
                    PatternParam(
                        name = PARAM_PREFIXES,
                        description = "Comma-separated allowed prefixes",
                        defaultValue = DEFAULT_PREFIXES_VALUE,
                    ),
                ),
                expand = { params ->
                    val raw = params[PARAM_PREFIXES] ?: DEFAULT_PREFIXES_VALUE
                    val prefixes = raw.parseCsvList()
                    val escaped = prefixes.toEscapedAlternation()
                    "(?:$escaped).+"
                },
            ),
        )
    }
}
