package io.karandash.validators

import io.karandash.api.errors.KarandashError
import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.utils.finding.FindingFactory

class SizeLimitsValidator(
    private val config: SizeLimitsConfig
) {
    private companion object {
        val DEFAULT_EXPLANATION = SizeLimitsText.EXPLANATION.en
    }

    fun validate(mr: MergeRequest): List<Finding> =
        buildList {
            config.severities.warnThreshold?.takeIf { config.severities.validWarnThreshold == null }?.let {
                add(
                    FindingFactory.warn(
                        rule = SizeLimitsRuleModule.RULE_ID,
                        message = "severities.warnThreshold $it is out of range and is ignored",
                        suggestion = "Set severities.warnThreshold to a value between 50% and 99%, e.g. 80%.",
                    ),
                )
            }

            val filteredChanges = mr.changes.filter { change ->
                if (config.excludeRenames && change.changeType == ChangeType.RENAMED) return@filter false
                if (config.excludeDeleted && change.changeType == ChangeType.DELETED) return@filter false
                true
            }

            config.maxFiles?.let { maxFiles ->
                val fileCount = filteredChanges.size
                addAll(checkLimit(fileCount, maxFiles, "files changed"))
            }

            config.maxLines?.let { maxLines ->
                val totalLines = filteredChanges.sumOf { it.additions + it.deletions }
                addAll(checkLimit(totalLines, maxLines, "lines changed"))
            }

            config.maxCommits?.let { maxCommits ->
                // the platform's own count when it has one: GitHub lists at most 250 commits
                val commitCount = mr.commitsTotal ?: mr.commits.size
                addAll(checkLimit(commitCount, maxCommits, "commits", truncated = mr.commitsTotal == null && mr.commitsTruncated))
            }
        }

    /**
     * Checks a metric [actual] against [limit] with optional warn threshold.
     *
     * - If [actual] > [limit], emits a finding at `severity.onViolation` level.
     * - Else if warn threshold is set and [actual] > `limit * warnThreshold`, emits a warning.
     * - Otherwise returns empty.
     */
    private fun checkLimit(
        actual: Int,
        limit: Int,
        label: String,
        truncated: Boolean = false,
    ): List<Finding> {
        if (actual > limit) {
            // a page cap stopped the fetch: [actual] is what was read, the MR has more
            val count = if (truncated) "more than $actual ($actual loaded; http.pagination.maxPages: 0 loads all)" else "$actual"
            return listOf(
                findingOfSeverity(
                    severity = config.severities.onViolation,
                    message = "Too many $label: $count (max: $limit)",
                    suggestion = "Split the MR: $actual $label is ${actual - limit} over the limit of $limit.",
                ).copy(errorCode = KarandashError.MR_TOO_LARGE.code),
            )
        }
        val threshold = config.severities.validWarnThreshold ?: return emptyList()
        val warnAt = (limit * threshold.fraction).toInt()
        if (actual > warnAt) {
            return listOf(
                FindingFactory.warn(
                    rule = SizeLimitsRuleModule.RULE_ID,
                    message = "Approaching $label limit: $actual (warn at $warnAt, max: $limit)",
                    suggestion = "Keep the MR at or under $limit $label: ${limit - actual} more allowed, or split it.",
                    explanation = DEFAULT_EXPLANATION,
                ),
            )
        }
        return emptyList()
    }

    private fun findingOfSeverity(
        severity: String,
        message: String,
        suggestion: String,
    ): Finding =
        when (severity) {
            "warning" -> FindingFactory.warn(
                rule = SizeLimitsRuleModule.RULE_ID,
                message = message,
                suggestion = suggestion,
                explanation = DEFAULT_EXPLANATION,
            )

            else -> FindingFactory.error(
                rule = SizeLimitsRuleModule.RULE_ID,
                message = message,
                suggestion = suggestion,
                explanation = DEFAULT_EXPLANATION,
            )
        }
}
