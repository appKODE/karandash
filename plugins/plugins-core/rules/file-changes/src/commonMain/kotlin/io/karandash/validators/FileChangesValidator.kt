package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.models.finding.Finding
import io.karandash.sdk.config.BlockMode
import io.karandash.utils.CollectionMatcher
import io.karandash.utils.GlobMatcher
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileChangesValidator(
    private val config: FileChangesConfig
) {
    private companion object {
        private val DEFAULT_SUGGESTION = FileChangesText.SUGGESTION.en
        private val DEFAULT_EXPLANATION = FileChangesText.EXPLANATION.en
    }

    /** True when at least one of [approvers] (usernames, optional leading `@`) has an APPROVED review on [mr]. */
    private fun approvedBy(
        mr: MergeRequest,
        approvers: List<String>,
    ): Boolean {
        val wanted = approvers.map { it.removePrefix("@").lowercase() }.toSet()
        return mr.approvals.any { it.state == ReviewState.APPROVED && it.user.username.lowercase() in wanted }
    }

    /**
     * Validates [mr]. [coverageGaps] is the `ci:coverage-gaps` enrichment (changed path to a list of
     * `{startLine,endLine}` ranges); it is only consulted when `requireCoverage` is on.
     *
     * Draft handling: the top-level `blockOnDraft` blocks the whole MR; a pattern's `blockOnDraft` blocks only
     * the files it matches and is not reported again when the top-level flag already fired.
     */
    fun validate(
        mr: MergeRequest,
        coverageGaps: JsonObject? = null,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (mr.draft) {
            config.blockOnDraft
                .applyTo(
                    FindingFactory.error(
                        rule = FileChangesRuleModule.RULE_ID,
                        message = "Draft MR is blocked by file changes rule",
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION
                    )
                )?.let(findings::add)
        }

        mr.changes.forEach { fileChange ->
            config.patterns.forEach { rule ->
                if (GlobMatcher.matches(fileChange.path, rule.pattern)) {
                    rule.requireApproval?.takeUnless { approvedBy(mr, it) }?.let { approvers ->
                        findings.add(
                            FindingFactory.warn(
                                rule = FileChangesRuleModule.RULE_ID,
                                message = "File '${fileChange.path}' requires approval from: ${approvers.joinToString(", ")}",
                                file = fileChange.path,
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION
                            )
                        )
                    }

                    rule.requireLabels?.let { requiredLabels ->
                        val missingLabels = CollectionMatcher.findMissing(mr.labels, requiredLabels)
                        if (missingLabels.isNotEmpty()) {
                            findings.add(
                                FindingFactory.error(
                                    rule = FileChangesRuleModule.RULE_ID,
                                    message = "File '${fileChange.path}' requires labels: ${missingLabels.joinToString(", ")}",
                                    file = fileChange.path,
                                    suggestion = DEFAULT_SUGGESTION,
                                    explanation = DEFAULT_EXPLANATION
                                )
                            )
                        }
                    }

                    if (rule.requireNonAuthorApproval && mr.approvals.none { it.user.username != mr.author.username }) {
                        findings.add(
                            FindingFactory.error(
                                rule = FileChangesRuleModule.RULE_ID,
                                message = "File '${fileChange.path}' requires an approval from someone other than the author",
                                file = fileChange.path,
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION
                            )
                        )
                    }

                    if (mr.draft && config.blockOnDraft == BlockMode.OFF) {
                        rule.blockOnDraft
                            .applyTo(
                                FindingFactory.error(
                                    rule = FileChangesRuleModule.RULE_ID,
                                    message = "Changes to '${fileChange.path}' are blocked in draft MRs",
                                    file = fileChange.path,
                                    suggestion = DEFAULT_SUGGESTION,
                                    explanation = DEFAULT_EXPLANATION
                                )
                            )?.let(findings::add)
                    }
                }
            }
        }

        config.requireApproval?.takeUnless { approvedBy(mr, it) }?.let { approvers ->
            if (mr.changes.isNotEmpty()) {
                findings.add(
                    FindingFactory.warn(
                        rule = FileChangesRuleModule.RULE_ID,
                        message = "MR requires approval from: ${approvers.joinToString(", ")}",
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            }
        }

        config.requireLabels?.let { requiredLabels ->
            val missingLabels = CollectionMatcher.findMissing(mr.labels, requiredLabels)
            if (missingLabels.isNotEmpty()) {
                findings.add(
                    FindingFactory.error(
                        rule = FileChangesRuleModule.RULE_ID,
                        message = "MR requires labels: ${missingLabels.joinToString(", ")}",
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            }
        }

        return findings + pathFindings(mr) + coverageFindings(mr, coverageGaps)
    }

    private fun pathFindings(mr: MergeRequest): List<Finding> {
        val blocked = mr.changes.filter { change -> config.blockedPaths.any { GlobMatcher.matches(change.path, it) } }.map {
            FindingFactory.error(
                rule = FileChangesRuleModule.RULE_ID,
                message = "File '${it.path}' is blocked by fileChanges.blockedPaths",
                file = it.path,
                suggestion = "Remove this file from the MR.",
                explanation = "The file matches a path this project does not allow in merge requests."
            )
        }
        val missing = config.requiredPaths.filter { glob -> mr.changes.none { GlobMatcher.matches(it.path, glob) } }.map {
            FindingFactory.error(
                rule = FileChangesRuleModule.RULE_ID,
                message = "MR must change a file matching '$it' (fileChanges.requiredPaths)",
                suggestion = "Add or update a file matching '$it'.",
                explanation = "Every MR must touch the paths listed in fileChanges.requiredPaths."
            )
        }
        return blocked + missing
    }

    /** One warning per changed file that has uncovered ranges in [coverageGaps]; empty unless `requireCoverage`. */
    fun coverageFindings(
        mr: MergeRequest,
        coverageGaps: JsonObject?,
    ): List<Finding> {
        if (!config.requireCoverage || coverageGaps == null) return emptyList()
        return mr.changes.mapNotNull { change ->
            val ranges = (coverageGaps[change.path] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            if (ranges.isEmpty()) return@mapNotNull null
            val text = ranges.joinToString(", ") {
                "${it.getValue("startLine").jsonPrimitive.int}-${it.getValue("endLine").jsonPrimitive.int}"
            }
            FindingFactory.warn(
                rule = FileChangesRuleModule.RULE_ID,
                message = "File '${change.path}' has uncovered changed lines: $text",
                file = change.path,
                suggestion = "Add tests covering the listed lines.",
                explanation = "Changed lines without test coverage are more likely to regress."
            )
        }
    }
}
