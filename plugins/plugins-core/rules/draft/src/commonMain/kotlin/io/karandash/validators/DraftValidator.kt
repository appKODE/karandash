package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.withText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.config.BlockMode
import io.karandash.utils.finding.FindingFactory

class DraftValidator(
    private val config: DraftConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private val wipErrors: List<Finding>
    private val compiledWipPatterns: List<Regex>

    init {
        val errors = mutableListOf<Finding>()
        compiledWipPatterns = RegexHelper.tryCompileAll(patterns, config.effectiveWipPatterns(), DraftRuleModule.RULE_ID, errors)
        wipErrors = errors
    }

    fun validate(mr: MergeRequest): List<Finding> {
        if (config.blockDraft == BlockMode.OFF) return emptyList()

        val draftFixSuggestions = listOf(FixSuggestion.SetDraft(draft = false))

        return buildList {
            addAll(wipErrors)

            if (mr.draft) {
                add(
                    FindingFactory
                        .error(
                            rule = DraftRuleModule.RULE_ID,
                            message = DraftText.IS_DRAFT.en,
                            fixSuggestions = draftFixSuggestions,
                        ).withText(
                            message = DraftText.IS_DRAFT.of(),
                            suggestion = DraftText.MARK_READY.of(),
                            explanation = DraftText.EXPLANATION.of(),
                        ).let { checkNotNull(config.blockDraft.applyTo(it)) }
                )
            } else {
                compiledWipPatterns.mapNotNull { it.find(mr.title) }.maxByOrNull { it.value.length }?.let { marker ->
                    add(
                        FindingFactory
                            .error(
                                rule = DraftRuleModule.RULE_ID,
                                message = DraftText.WIP_TITLE.en,
                                fixSuggestions = draftFixSuggestions,
                            ).withText(
                                message = DraftText.WIP_TITLE.of(),
                                suggestion = DraftText.REMOVE_MARKER.of(marker.value),
                                explanation = DraftText.EXPLANATION.of(),
                            ).let { checkNotNull(config.blockDraft.applyTo(it)) }
                    )
                }
            }
        }
    }
}
