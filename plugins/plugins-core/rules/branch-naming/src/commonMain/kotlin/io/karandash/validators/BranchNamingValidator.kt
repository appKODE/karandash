package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.codeSpan
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.CaseValidator
import io.karandash.patterns.PatternFix
import io.karandash.utils.finding.FindingFactory

class BranchNamingValidator(
    private val config: BranchNamingConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        val EXPLANATION = BranchNamingText.EXPLANATION.en
        const val SUBJECT = "branch name"
    }

    private val rawPatterns: List<String> = when {
        config.allowedPatterns.isNotEmpty() -> config.allowedPatterns
        config.pattern != null -> listOf(config.pattern)
        else -> emptyList()
    }
    private val initErrors: List<Finding>
    private val compiledPatterns: List<Regex>

    init {
        val errors = mutableListOf<Finding>()
        compiledPatterns = RegexHelper.tryCompileAll(patterns, rawPatterns, BranchNamingRuleModule.RULE_ID, errors)
        if (config.allowedPatterns.isNotEmpty() && config.pattern != null) {
            errors += FindingFactory.warn(
                rule = BranchNamingRuleModule.RULE_ID,
                message = "Both pattern and allowedPatterns are set; allowedPatterns is used and pattern is ignored.",
                suggestion = "Remove pattern, or move it into allowedPatterns.",
                explanation = EXPLANATION,
            )
        }
        initErrors = errors
    }

    fun validate(mr: MergeRequest): List<Finding> =
        buildList {
            addAll(initErrors)

            if (rawPatterns.isNotEmpty()) {
                if (compiledPatterns.isNotEmpty()) {
                    if (config.requireSourceMatch && compiledPatterns.none { it.matches(mr.sourceBranch) }) {
                        add(patternFinding("Source branch", mr.sourceBranch))
                    }
                    if (config.requireTargetMatch && compiledPatterns.none { it.matches(mr.targetBranch) }) {
                        add(patternFinding("Target branch", mr.targetBranch))
                    }
                }
            } else {
                // Structured validation: prefix, case, max-length
                if (config.requireSourceMatch) {
                    addAll(validateStructured(mr.sourceBranch, isSource = true))
                }
                if (config.requireTargetMatch) {
                    addAll(validateStructured(mr.targetBranch, isSource = false))
                }
            }
        }

    /**
     * The fix of the closest allowed pattern: a deterministic correction first, else the latest mismatch. On a tie
     * the first configured pattern wins (`maxWith` keeps the first maximum).
     */
    private fun patternFinding(
        branchLabel: String,
        branch: String,
    ): Finding {
        // The fix stays a suggestion, never a FixSuggestion: autofix cannot rename the MR's source branch (N/A by design).
        val fix = rawPatterns
            .map { PatternFix.of(it, branch, subject = SUBJECT) }
            .maxWith(compareBy<PatternFix>({ it.deterministic }, { it.mismatch?.position ?: -1 }))
        return FindingFactory.error(
            rule = BranchNamingRuleModule.RULE_ID,
            message = "$branchLabel '$branch' does not match any allowed pattern.",
            suggestion = fix.suggestion,
            explanation = EXPLANATION,
            metadata = fix.metadata() + (WellKnownMetadataKeys.PATTERN to rawPatterns.joinToString(", ")),
        )
    }

    private fun validateStructured(
        branch: String,
        isSource: Boolean
    ): List<Finding> {
        val branchLabel = if (isSource) "Source branch" else "Target branch"
        return buildList {
            config.prefixOneOf?.let { prefixes ->
                if (prefixes.isNotEmpty() && prefixes.none { branch.startsWith(it) }) {
                    add(
                        FindingFactory.error(
                            rule = BranchNamingRuleModule.RULE_ID,
                            message = "$branchLabel '$branch' has none of the allowed prefixes.",
                            suggestion = "Rename the branch to start with one of " +
                                prefixes.joinToString(", ") { codeSpan(it) } + ".",
                            explanation = EXPLANATION
                        )
                    )
                }
            }

            config.case?.let { caseType ->
                val branchBody = stripKnownPrefix(branch, config.prefixOneOf)
                if (!CaseValidator.matches(branchBody, caseType)) {
                    add(
                        FindingFactory.error(
                            rule = BranchNamingRuleModule.RULE_ID,
                            // The description ends in an example, which belongs in the suggestion only.
                            message = "$branchLabel '$branch' body is not " +
                                CaseValidator.descriptionOf(caseType).substringBefore(" (") + ".",
                            suggestion = "Rename the branch body to use ${CaseValidator.descriptionOf(caseType)}.",
                            explanation = EXPLANATION
                        )
                    )
                }
            }

            config.maxLength?.let { max ->
                if (branch.length > max) {
                    add(
                        FindingFactory.error(
                            rule = BranchNamingRuleModule.RULE_ID,
                            message = "$branchLabel '$branch' exceeds max length $max (actual: ${branch.length}).",
                            suggestion = "Shorten the branch name to at most $max characters.",
                            explanation = EXPLANATION
                        )
                    )
                }
            }
        }
    }

    /** Strips the matched prefix so case validation applies to the branch body only. */
    private fun stripKnownPrefix(
        branch: String,
        prefixes: List<String>?
    ): String {
        if (prefixes.isNullOrEmpty()) return branch
        val matched = prefixes.firstOrNull { branch.startsWith(it) } ?: return branch
        return branch.removePrefix(matched)
    }
}
