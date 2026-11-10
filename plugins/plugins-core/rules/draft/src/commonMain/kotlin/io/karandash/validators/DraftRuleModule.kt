package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.patterns.PatternDefinition
import io.karandash.patterns.PatternRegistry
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class DraftRuleModule : AbstractTypedSingleRuleModule<DraftConfig>(DraftConfig.serializer()) {
    companion object {
        const val RULE_ID = "draftPrevention"
        private const val WIP_MARKER_PATTERN = "\\bWIP\\b|\\[WIP\\]|\\bDraft:|\\bDO NOT MERGE\\b"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Draft status indicates a merge request is work-in-progress and not ready for review or merging. " +
            "This prevents premature merging of incomplete work."

    override val name: String = "Draft Prevention Rule"
    override val description: String = "Prevents merging draft/WIP merge requests."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: DraftConfig
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: DraftConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = DraftValidator(config, patterns).validate(mr)

    override fun registerPatterns(registry: PatternRegistry) {
        registry.register(
            PatternDefinition(
                name = "wip-marker",
                description = "Matches common WIP/Draft title markers: WIP, [WIP], Draft:, DO NOT MERGE",
                params = emptyList(),
                expand = { WIP_MARKER_PATTERN },
            ),
        )
    }
}
