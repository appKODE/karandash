package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.patterns.PatternDefinition
import io.karandash.patterns.PatternParam
import io.karandash.patterns.PatternRegistry
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import io.karandash.utils.BoundedCache
import io.karandash.utils.mr.conventionalCommitPattern
import io.karandash.utils.parseCsvList

class TitleFormatRuleModule : AbstractTypedSingleRuleModule<TitleFormatConfig>(TitleFormatConfig.serializer()) {
    companion object {
        const val RULE_ID = "titleFormat"
        private const val PARAM_TYPES = "types"
        private const val DEFAULT_TYPES_VALUE = "feat,fix,chore,docs,test,refactor,style,perf"
        private const val PATTERN_CACHE_SIZE = 64
    }

    /** Compiled user patterns keyed by pattern string; typed config arrives per call, so it cannot be built once. */
    private val patternCache = BoundedCache<String, Regex>(maxEntries = PATTERN_CACHE_SIZE)

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "The merge request title should follow a specific format to ensure consistency and traceability. " +
            "A well-formatted title helps team members quickly understand the purpose of the change."

    override val name: String = "Title Format Rule"
    override val description: String = "Validates MR/PR title format using a regex pattern and length constraints."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: TitleFormatConfig
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: TitleFormatConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = TitleFormatValidator(config, patternCache, patterns).validate(mr).map { it.withFixedText(TitleFormatText.entries) }

    override fun registerPatterns(registry: PatternRegistry) {
        registry.register(
            PatternDefinition(
                name = "conventional-commit",
                description = "Conventional commit title format. " +
                    "Use @types:feat,fix,docs to restrict allowed types.",
                params = listOf(
                    PatternParam(
                        name = PARAM_TYPES,
                        description = "Comma-separated allowed types",
                        defaultValue = DEFAULT_TYPES_VALUE,
                    ),
                ),
                expand = { params ->
                    val raw = params[PARAM_TYPES] ?: DEFAULT_TYPES_VALUE
                    conventionalCommitPattern(raw.parseCsvList())
                },
            ),
        )
    }
}
