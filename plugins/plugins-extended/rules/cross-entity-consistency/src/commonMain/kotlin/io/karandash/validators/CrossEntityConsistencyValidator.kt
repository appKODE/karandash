package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.utils.finding.CommitFindingGroup
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.shortSha

class CrossEntityConsistencyValidator(
    private val config: CrossEntityConsistencyConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        val DEFAULT_EXPLANATION = CrossEntityText.EXPLANATION.en
    }

    private val mrSourceLabel: String = when (config.mrTaskSource) {
        "description" -> "MR description"
        "both" -> "MR title/description"
        else -> "MR title"
    }

    private val defaultSuggestion: String =
        "Ensure task IDs in commits match those in the $mrSourceLabel."

    private val compiledTaskPattern: Regex?
    private val patternError: Finding?

    init {
        val errors = mutableListOf<Finding>()
        compiledTaskPattern =
            RegexHelper.tryCompile(patterns, config.effectiveTaskIdPattern(), CrossEntityConsistencyRuleModule.RULE_ID, errors)
        patternError = errors.firstOrNull()
    }

    fun validate(mr: MergeRequest): List<Finding> {
        if (compiledTaskPattern == null) {
            return listOfNotNull(patternError)
        }

        return buildList {
            val mrTaskIds = extractMrTaskIds(mr)

            if (config.commitTasksSubsetOfMr) {
                validateCommitTasksSubset(mr, mrTaskIds, this)
            }

            if (config.changelogConsistency) {
                validateChangelogConsistency(mr, mrTaskIds, this)
            }

            if (config.changelogDuplicateDetection) {
                validateChangelogNoDuplicates(mr, this)
            }
        }
    }

    private fun extractMrTaskIds(mr: MergeRequest): Set<String> {
        val titleIds = if (config.mrTaskSource == "title" || config.mrTaskSource == "both") {
            extractTaskIds(mr.title)
        } else {
            emptySet()
        }
        val descriptionIds = if (config.mrTaskSource == "description" || config.mrTaskSource == "both") {
            mr.description?.let { extractTaskIds(it) } ?: emptySet()
        } else {
            emptySet()
        }
        return titleIds + descriptionIds
    }

    private fun extractTaskIds(text: String): Set<String> {
        val pattern = compiledTaskPattern ?: return emptySet()
        return pattern.findAll(text).map { it.value }.toSet()
    }

    private fun validateCommitTasksSubset(
        mr: MergeRequest,
        mrTaskIds: Set<String>,
        findings: MutableList<Finding>,
    ) {
        val perCommit = mr.commits.mapNotNull { commit ->
            val extraTasks = extractTaskIds(commit.message.lines().first()) - mrTaskIds
            if (extraTasks.isEmpty()) return@mapNotNull null
            val sha = commit.sha.shortSha()
            val extra = extraTasks.joinToString()
            val message = if (mrTaskIds.isEmpty()) {
                "Commit $sha mentions $extra, but the $mrSourceLabel has no task ID."
            } else {
                "Commit $sha mentions $extra, which is not in the $mrSourceLabel (${mrTaskIds.joinToString()})."
            }
            val pronoun = if (extraTasks.size == 1) "it" else "them"
            sha to
                FindingFactory.error(
                    rule = CrossEntityConsistencyRuleModule.RULE_ID,
                    message = message,
                    suggestion = "Add ${extraTasks.joinToString { "[$it]" }} to the $mrSourceLabel " +
                        "or remove $pronoun from the commit subject.",
                    explanation = DEFAULT_EXPLANATION,
                )
        }
        findings.addAll(CommitFindingGroup.collapse(perCommit, "mention tasks not in the $mrSourceLabel"))
    }

    private fun validateChangelogConsistency(
        mr: MergeRequest,
        mrTaskIds: Set<String>,
        findings: MutableList<Finding>,
    ) {
        val allChangelogTaskIds = mutableSetOf<String>()

        mr.commits.forEach { commit ->
            val bodyLines = commit.message.lines().drop(1)
            bodyLines.forEach { line ->
                if (line.trimStart().startsWith(config.changelogPrefix)) {
                    allChangelogTaskIds.addAll(extractTaskIds(line))
                }
            }
        }

        val extraChangelogTasks = allChangelogTaskIds - mrTaskIds
        if (extraChangelogTasks.isNotEmpty()) {
            val tickets = extraChangelogTasks.joinToString()
            findings.add(
                FindingFactory.error(
                    rule = CrossEntityConsistencyRuleModule.RULE_ID,
                    message = "CHANGELOG mentions ${extraChangelogTasks.size} " +
                        "${if (extraChangelogTasks.size == 1) "task" else "tasks"} " +
                        "not in the $mrSourceLabel (${CommitFindingGroup.shortList(extraChangelogTasks)})",
                    suggestion = "Add $tickets to the $mrSourceLabel, or remove $tickets from the CHANGELOG.",
                    explanation = DEFAULT_EXPLANATION
                ),
            )
        }

        if (config.changelogCoverage && allChangelogTaskIds.isNotEmpty()) {
            // Only fire when CHANGELOG entries already exist - checks consistency between
            // written entries and MR title. The "no CHANGELOG at all" case is the job of the
            // commit-body rule (which can be gated by a project-specific policy condition).
            val missing = mrTaskIds - allChangelogTaskIds
            if (missing.isNotEmpty()) {
                val tickets = missing.joinToString()
                findings.add(
                    FindingFactory.error(
                        rule = CrossEntityConsistencyRuleModule.RULE_ID,
                        message = "$mrSourceLabel ticket(s) not covered by any CHANGELOG entry: $tickets",
                        suggestion = "Add a CHANGELOG line for $tickets to one of the commits, " +
                            "or remove $tickets from the $mrSourceLabel.",
                        explanation = "Each ticket in the MR scope should produce a release-note entry."
                    ),
                )
            }
        }
    }

    private fun validateChangelogNoDuplicates(
        mr: MergeRequest,
        findings: MutableList<Finding>,
    ) {
        if (config.changelogDuplicateRequiresMatchingText) {
            val entries = mutableListOf<Pair<String, String>>()
            mr.commits.forEach { commit ->
                val bodyLines = commit.message.lines().drop(1)
                bodyLines.forEach { line ->
                    if (line.trimStart().startsWith(config.changelogPrefix)) {
                        compiledTaskPattern?.findAll(line)?.forEach { match ->
                            entries.add(match.value to line.trim())
                        }
                    }
                }
            }
            val duplicates = entries
                .groupBy { it }
                .filter { it.value.size > 1 }
                .keys
                .map { it.first }
                .toSet()
            if (duplicates.isNotEmpty()) {
                findings.add(
                    FindingFactory.error(
                        rule = CrossEntityConsistencyRuleModule.RULE_ID,
                        message = "Duplicate task IDs found in CHANGELOG entries: ${duplicates.joinToString()}",
                        suggestion = "Each task ID should appear in CHANGELOG only once across all commits.",
                        explanation = "Duplicate CHANGELOG entries for the same task ID indicate redundant or conflicting changelog notes."
                    ),
                )
            }
        } else {
            val allChangelogTaskIds = mutableListOf<String>()
            mr.commits.forEach { commit ->
                val bodyLines = commit.message.lines().drop(1)
                bodyLines.forEach { line ->
                    if (line.trimStart().startsWith(config.changelogPrefix)) {
                        compiledTaskPattern?.findAll(line)?.forEach { match ->
                            allChangelogTaskIds.add(match.value)
                        }
                    }
                }
            }
            val duplicates = allChangelogTaskIds
                .groupBy { it }
                .filter { it.value.size > 1 }
                .keys
            if (duplicates.isNotEmpty()) {
                findings.add(
                    FindingFactory.error(
                        rule = CrossEntityConsistencyRuleModule.RULE_ID,
                        message = "Duplicate task IDs found in CHANGELOG entries: ${duplicates.joinToString()}",
                        suggestion = "Each task ID should appear in CHANGELOG only once across all commits.",
                        explanation = "Duplicate CHANGELOG entries for the same task ID indicate redundant or conflicting changelog notes."
                    ),
                )
            }
        }
    }
}
