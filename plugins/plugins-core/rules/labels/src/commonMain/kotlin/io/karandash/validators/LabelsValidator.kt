package io.karandash.validators

import io.karandash.api.errors.KarandashError
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.withFixedText
import io.karandash.models.finding.withText
import io.karandash.utils.CollectionMatcher
import io.karandash.utils.GlobMatcher
import io.karandash.utils.finding.FindingFactory

class LabelsValidator(
    private val config: LabelsConfig
) {
    private companion object {
        val DEFAULT_SUGGESTION = LabelsText.SUGGESTION.en
        val DEFAULT_EXPLANATION = LabelsText.EXPLANATION.en
        const val WILDCARD = '*'
    }

    /** `requiredLabels`/`forbiddenLabels` entries containing `*` are globs (`type::*`); others match exactly. */
    private fun labelMatches(
        label: String,
        pattern: String,
    ): Boolean = if (WILDCARD in pattern) GlobMatcher.matches(label, pattern) else label == pattern

    fun validate(mr: MergeRequest): List<Finding> {
        val findings = mutableListOf<Finding>()

        config.requiredLabels.forEach { requiredLabel ->
            if (mr.labels.none { labelMatches(it, requiredLabel) }) {
                findings.add(
                    FindingFactory
                        .error(
                            rule = LabelsRuleModule.RULE_ID,
                            message = LabelsText.MISSING.en,
                            suggestion = DEFAULT_SUGGESTION,
                            explanation = DEFAULT_EXPLANATION,
                            // A wildcard names no concrete label to add.
                            fixSuggestions = if (WILDCARD in requiredLabel) emptyList() else listOf(FixSuggestion.AddLabel(requiredLabel)),
                        ).copy(errorCode = KarandashError.MISSING_REQUIRED_LABEL.code)
                        .withText(message = LabelsText.MISSING.of(requiredLabel)),
                )
            }
        }

        config.forbiddenLabels.forEach { forbiddenLabel ->
            mr.labels.filter { labelMatches(it, forbiddenLabel) }.forEach { label ->
                findings.add(
                    FindingFactory
                        .error(
                            rule = LabelsRuleModule.RULE_ID,
                            message = LabelsText.FORBIDDEN.en,
                            suggestion = DEFAULT_SUGGESTION,
                            explanation = DEFAULT_EXPLANATION,
                            fixSuggestions = listOf(FixSuggestion.RemoveLabel(label)),
                        ).copy(errorCode = KarandashError.FORBIDDEN_LABEL_PRESENT.code)
                        .withText(message = LabelsText.FORBIDDEN.of(label)),
                )
            }
        }

        config.conditionalLabels.forEach { conditional ->
            if (conditionMatches(conditional.condition, mr.labels)) {
                conditional.requiredLabels.forEach { requiredLabel ->
                    if (requiredLabel !in mr.labels) {
                        findings.add(
                            FindingFactory.error(
                                rule = LabelsRuleModule.RULE_ID,
                                message = "Conditional requirement not met: label '$requiredLabel' is required when ${
                                    describeCondition(
                                        conditional.condition
                                    )
                                }",
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION
                            )
                        )
                    }
                }

                conditional.forbiddenLabels.forEach { forbiddenLabel ->
                    if (forbiddenLabel in mr.labels) {
                        findings.add(
                            FindingFactory.error(
                                rule = LabelsRuleModule.RULE_ID,
                                message = "Conditional requirement not met: label '$forbiddenLabel' is forbidden when ${
                                    describeCondition(
                                        conditional.condition
                                    )
                                }",
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION
                            )
                        )
                    }
                }
            }
        }

        return findings.map { it.withFixedText(LabelsText.entries) }
    }

    private fun conditionMatches(
        condition: LabelCondition,
        labels: List<String>
    ): Boolean {
        val checks = listOfNotNull(
            condition.hasLabel?.let { it in labels },
            condition.hasAnyLabel?.let { CollectionMatcher.hasAny(labels, it) },
            condition.hasAllLabels?.let { CollectionMatcher.hasAll(labels, it) },
        )
        return checks.isNotEmpty() && checks.all { it }
    }

    private fun describeCondition(condition: LabelCondition): String {
        val parts = listOfNotNull(
            condition.hasLabel?.let { "label '$it' is present" },
            condition.hasAnyLabel?.let { "any of labels [${it.joinToString(", ") { l -> "'$l'" }}] is present" },
            condition.hasAllLabels?.let { "all labels [${it.joinToString(", ") { l -> "'$l'" }}] are present" },
        )
        return parts.joinToString(" and ").ifEmpty { "condition is met" }
    }
}
