package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.codeSpan
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.PatternFix
import io.karandash.utils.finding.FindingFactory

class DescriptionFormatValidator(
    private val config: DescriptionFormatConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        val DEFAULT_EXPLANATION = DescriptionFormatText.EXPLANATION.en
    }

    private val compiledSectionPatterns: List<Pair<SectionRule, Regex?>>
    private val sectionPatternErrors: List<Finding>

    init {
        val sectionErrors = mutableListOf<Finding>()
        compiledSectionPatterns = config.mrSections.map { section ->
            section to RegexHelper.tryCompile(patterns, section.pattern, DescriptionFormatRuleModule.RULE_ID, sectionErrors)
        }
        sectionPatternErrors = sectionErrors.toList()
    }

    fun validate(mr: MergeRequest): List<Finding> =
        buildList {
            addAll(sectionPatternErrors)
            validateMrDescription(mr, this)
        }

    private fun validateMrDescription(
        mr: MergeRequest,
        findings: MutableList<Finding>
    ) {
        val description = mr.description
        if (config.mrSections.isEmpty()) return

        if (description.isNullOrBlank()) {
            val requiredSections = config.mrSections.filter { it.required }
            if (requiredSections.isNotEmpty()) {
                findings.add(
                    sectionFinding(
                        requiredSections.first(),
                        description,
                        "MR description is empty; required sections are missing: ${requiredSections.joinToString { it.name }}.",
                    ),
                )
            }
            return
        }

        val descriptionLines = description.lines()

        for ((section, compiledPattern) in compiledSectionPatterns) {
            if (compiledPattern == null) continue

            val matchFound = descriptionLines.any { line -> compiledPattern.containsMatchIn(line) }

            if (!matchFound && section.required) {
                findings.add(sectionFinding(section, description, "MR description is missing the required section ${section.name}."))
            }
        }
    }

    /**
     * A section is searched for anywhere in the description, so the fix is an example of it, not a correction;
     * when the pattern yields one, [FixSuggestion.SetDescription] appends it to [description].
     */
    private fun sectionFinding(
        section: SectionRule,
        description: String?,
        message: String,
    ): Finding {
        val fix = PatternFix.of(section.pattern, actual = "", subject = "description", diagnose = false)
        return FindingFactory.error(
            rule = DescriptionFormatRuleModule.RULE_ID,
            message = message,
            suggestion = fix.value?.let { "Add the ${section.name} section, e.g. ${codeSpan(it)}." } ?: "Add the ${section.name} section.",
            explanation = DEFAULT_EXPLANATION,
            metadata = fix.metadata() + (WellKnownMetadataKeys.PATTERN to section.pattern),
            fixSuggestions = listOfNotNull(
                fix.value?.let {
                    FixSuggestion.SetDescription(
                        listOfNotNull(description?.trimEnd()?.ifEmpty { null }, it).joinToString("\n\n")
                    )
                },
            ),
        )
    }
}
