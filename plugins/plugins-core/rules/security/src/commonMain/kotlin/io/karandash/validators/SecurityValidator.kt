package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.utils.GlobMatcher
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.addedLinesWithNumbers
import io.karandash.utils.mr.shortSha

class SecurityValidator(
    private val config: SecurityConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        val DEFAULT_SUGGESTION = SecurityText.SUGGESTION.en
        val LARGE_DIFF_SUGGESTION = SecurityText.LARGE_DIFF_SUGGESTION.en
        val LARGE_DIFF_EXPLANATION = SecurityText.LARGE_DIFF_EXPLANATION.en
        val DEFAULT_EXPLANATION = SecurityText.EXPLANATION.en
    }

    private val compiledSecretPatterns: List<Regex>
    private val patternCompilationErrors: List<Finding>

    init {
        val errors = mutableListOf<Finding>()
        compiledSecretPatterns = RegexHelper.tryCompileAll(
            patterns,
            config.secretPatterns,
            SecurityRuleModule.RULE_ID,
            errors
        )
        patternCompilationErrors = errors
    }

    private val maxDiffScanSize: Long = config.scan.maxDiffSize.bytes

    /**
     * Creates a finding with configurable severity level.
     * `"warning"` and `"info"` are lowered; `"error"` is the default (values are validated at config load).
     */
    private fun findingOfSeverity(
        severity: String,
        rule: String,
        message: String,
        file: String? = null,
        line: Int? = null,
        suggestion: String?,
        explanation: String?,
    ): Finding =
        when (severity.lowercase()) {
            "warning" -> FindingFactory.warn(rule, message, file, line, explanation, suggestion)
            "info" -> FindingFactory.info(rule, message, file, line, explanation, suggestion)
            else -> FindingFactory.error(rule, message, file, line, explanation, suggestion)
        }

    fun validate(mr: MergeRequest): List<Finding> =
        buildList {
            if (config.scanForSecrets) {
                addAll(scanForSecrets(mr))
            }
            addAll(checkSensitiveFiles(mr))
        }

    private fun scanForSecrets(mr: MergeRequest): List<Finding> {
        return buildList {
            addAll(patternCompilationErrors)

            // If no valid patterns, return early with error
            if (compiledSecretPatterns.isEmpty() && config.secretPatterns.isNotEmpty()) {
                return@buildList
            }

            // Scan commit messages for secrets
            mr.commits.forEach { commit ->
                compiledSecretPatterns.forEach { regex ->
                    if (regex.containsMatchIn(commit.message)) {
                        val shortId = commit.sha.shortSha()
                        add(
                            findingOfSeverity(
                                severity = config.severities.secretFound,
                                rule = SecurityRuleModule.RULE_ID,
                                message = "Potential secret detected in commit message: $shortId",
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION,
                            )
                        )
                    }
                }
            }

            // Scan added lines in file diffs for secrets (only new lines, not removed)
            mr.changes.forEach { change ->
                val diff = change.diffContent ?: return@forEach
                val diffBytes = diff.utf8ByteLength()
                if (diffBytes > maxDiffScanSize) {
                    add(
                        findingOfSeverity(
                            severity = config.severities.largeDiffSkipped,
                            rule = SecurityRuleModule.RULE_ID,
                            message = "Diff too large to scan ($diffBytes bytes > $maxDiffScanSize): ${change.path}",
                            file = change.path,
                            suggestion = LARGE_DIFF_SUGGESTION,
                            explanation = LARGE_DIFF_EXPLANATION,
                        )
                    )
                    return@forEach
                }
                val addedLines = change.addedLinesWithNumbers()
                for (pattern in compiledSecretPatterns) {
                    val match = addedLines.firstOrNull { pattern.containsMatchIn(it.content) }
                    if (match != null) {
                        add(
                            findingOfSeverity(
                                severity = config.severities.secretFound,
                                rule = SecurityRuleModule.RULE_ID,
                                message = SecurityText.SECRET_IN_ADDED_LINE.en,
                                file = change.path,
                                line = match.lineNumber,
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION,
                            )
                        )
                    }
                }
            }

            // Scan MR title and description for secrets
            compiledSecretPatterns.forEach { regex ->
                if (regex.containsMatchIn(mr.title)) {
                    add(
                        findingOfSeverity(
                            severity = config.severities.secretFound,
                            rule = SecurityRuleModule.RULE_ID,
                            message = "Potential secret detected in MR title",
                            suggestion = DEFAULT_SUGGESTION,
                            explanation = DEFAULT_EXPLANATION,
                        )
                    )
                }
                mr.description?.let { desc ->
                    if (regex.containsMatchIn(desc)) {
                        add(
                            findingOfSeverity(
                                severity = config.severities.secretFound,
                                rule = SecurityRuleModule.RULE_ID,
                                message = "Potential secret detected in MR description",
                                suggestion = DEFAULT_SUGGESTION,
                                explanation = DEFAULT_EXPLANATION,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun checkSensitiveFiles(mr: MergeRequest): List<Finding> =
        buildList {
            mr.changes.forEach { change ->
                val isSensitive = config.sensitiveFiles.any { pattern ->
                    GlobMatcher.matches(change.path, pattern)
                }

                if (isSensitive) {
                    add(
                        FindingFactory.warn(
                            rule = SecurityRuleModule.RULE_ID,
                            message = "Sensitive file change detected: ${change.path}",
                            file = change.path,
                            suggestion = DEFAULT_SUGGESTION,
                            explanation = DEFAULT_EXPLANATION
                        )
                    )
                }
            }
        }
}
