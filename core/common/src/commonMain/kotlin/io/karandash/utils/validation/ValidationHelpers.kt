package io.karandash.utils.validation

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.filesMatching

/**
 * Validate each file matching a pattern. Callback receives each matching file
 * and returns findings for that file.
 */
fun MergeRequest.validateFiles(
    pattern: Regex,
    validator: (FileChange) -> List<Finding>
): List<Finding> = filesMatching(pattern).flatMap(validator)

/**
 * Check a condition and return a finding if it fails.
 */
fun checkRule(
    rule: String,
    severity: Severity,
    condition: Boolean,
    message: String,
    explanation: String? = null
): List<Finding> =
    if (!condition) {
        listOf(FindingFactory.error(rule, message, explanation = explanation).copy(severity = severity))
    } else {
        emptyList()
    }
