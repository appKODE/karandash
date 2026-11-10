package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.codeSpan
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.utils.finding.CommitFindingGroup
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.shortSha

class ContextTagValidator(
    private val config: ContextTagConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        const val EXPLANATION = "Context tags categorize changes for routing and reporting."
    }

    private val compiledTagPattern: Regex?
    private val patternError: Finding?

    init {
        val errors = mutableListOf<Finding>()
        compiledTagPattern = RegexHelper.tryCompile(patterns, config.effectiveTagPattern(), ContextTagRuleModule.RULE_ID, errors)
        patternError = errors.firstOrNull()
    }

    fun validate(mr: MergeRequest): List<Finding> {
        if (config.allowed.isEmpty()) return emptyList()
        if (compiledTagPattern == null) return listOfNotNull(patternError)

        val titleFinding = if (config.checkMrTitle) checkTags(mr.title, "MR title") else null
        val commitFindings = if (config.checkCommitTitles) {
            mr.commits.mapNotNull { commit ->
                val sha = commit.sha.shortSha()
                checkTags(commit.message.lines().first(), "commit $sha")?.let { sha to it }
            }
        } else {
            emptyList()
        }
        // One folded finding per kind (no tag / several tags / wrong case), so a long MR is not a wall of repeats.
        return listOfNotNull(titleFinding) +
            commitFindings
                .groupBy { summaryOf(it.second) }
                .flatMap { (summary, group) -> CommitFindingGroup.collapse(group, summary) }
    }

    private fun summaryOf(finding: Finding): String =
        when {
            finding.message.startsWith("No context tag") -> "have no context tag"
            finding.message.startsWith("Multiple context tags") -> "have more than one context tag"
            else -> "use a context tag in the wrong case"
        }

    private fun checkTags(
        text: String,
        entityName: String,
    ): Finding? {
        val pattern = compiledTagPattern ?: return null
        val allTags = pattern
            .findAll(text)
            .map { it.groupValues.getOrNull(1) ?: it.value }
            .toList()
        val foundTags = allTags.filter { tag -> matchesAllowed(tag) }

        if (foundTags.isEmpty()) {
            return wrongCase(allTags, entityName) ?: FindingFactory.error(
                rule = ContextTagRuleModule.RULE_ID,
                message = "No context tag found in $entityName.",
                suggestion = "Add one of the allowed context tags to the $entityName: " +
                    config.allowed.joinToString { codeSpan(config.formatTag(it)) } + ".",
                explanation = EXPLANATION,
            )
        }

        if (config.exactlyOne && foundTags.size > 1) {
            return FindingFactory.error(
                rule = ContextTagRuleModule.RULE_ID,
                message = "Multiple context tags found in $entityName: ${foundTags.joinToString(
                    transform = config::formatTag
                )}.",
                suggestion = "Keep only one context tag in the $entityName, e.g. ${codeSpan(config.formatTag(foundTags.first()))}.",
                explanation = EXPLANATION,
            )
        }
        return null
    }

    /** With `caseSensitive: true`, a tag that differs from an allowed one only in case gets its own finding. */
    private fun wrongCase(
        tags: List<String>,
        entityName: String,
    ): Finding? {
        if (!config.caseSensitive) return null
        val (tag, allowed) = tags.firstNotNullOfOrNull { tag ->
            config.allowed.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { tag to it }
        } ?: return null
        return FindingFactory.error(
            rule = ContextTagRuleModule.RULE_ID,
            message = "${entityName.replaceFirstChar { it.uppercase() }}: tag ${config.formatTag(tag)} is not accepted " +
                "— tags are case-sensitive here.",
            suggestion = "Use ${config.formatTag(allowed)} exactly, or set caseSensitive: false.",
            explanation = EXPLANATION,
        )
    }

    private fun matchesAllowed(tag: String): Boolean =
        if (config.caseSensitive) {
            tag in config.allowed
        } else {
            config.allowed.any { it.equals(tag, ignoreCase = true) }
        }
}
