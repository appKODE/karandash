package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.diagnostics.FindingDropReason
import io.karandash.utils.mr.addedLinesWithNumbers

/**
 * Post-dispatch filter that drops AI-produced findings whose location does not
 * map to a real changed line in the MR.
 *
 * Context providers (related files, full files, conventions) feed the AI with
 * read-only material it might otherwise reference in a finding. Without this
 * validator, "hallucinated" findings (file not in the MR, line not in a changed
 * hunk, line numbers outside the file) would reach reporters and confuse users.
 *
 * Reads the added lines from [MergeRequest.changes] - the **full** diff,
 * *not* any truncated view produced by [HunkAwareTruncator]. This matters because
 * truncation may drop hunks that the AI still (correctly) referenced.
 *
 * Under `strict`, findings on paths missing from the diff or deleted at head are dropped, and line anchors go
 * through [AnchorResolver]: snapped onto added code, dropped when on a line the MR did not add, or demoted to an
 * unverified file-level [Location.FileRef] flagged with [AiFindingMetadataKeys.LINE_DEMOTED].
 *
 * MR-level findings (`location == null`) and [Location.CommitRef] findings always
 * pass - they are not subject to per-hunk validation.
 */
object FindingLocationValidator {
    /**
     * @param findings Raw findings returned by the AI analyzer.
     * @param mr The merge request being validated (used for the reference file set).
     * @param ruleId The rule being validated; diagnostics log each drop under the finding's own rule (a
     *     code-suggestion twin validated with its comments logs as `code-suggestions`), this only when it has none.
     * @param strict When true (default), hallucinated paths are dropped and line anchors are resolved by
     *     [AnchorResolver]. When false, both are kept as-is but still logged.
     * @param diagnostics Optional diagnostics sink for structured logging.
     * @return Findings whose location was verified (or all of them when `strict=false`).
     */
    fun validate(
        findings: List<Finding>,
        mr: MergeRequest,
        ruleId: String,
        strict: Boolean = true,
        diagnostics: ContextQualityDiagnostics? = null,
    ): List<Finding> {
        if (findings.isEmpty()) return findings
        val headPaths = mr.changes
            .filter { it.changeType != ChangeType.DELETED }
            .map { it.path }
            .toSet()
        // null value: the diff is unknown or has no added lines (pure rename, mode change, binary), so lines in
        // that file cannot be checked and the finding is kept as is
        val addedLinesByPath: Map<String, Map<Int, String>?> = mr.changes.associate { change ->
            change.path to change.addedLinesWithNumbers().associate { it.lineNumber to it.content }.ifEmpty { null }
        }

        return findings.mapNotNull { finding ->
            when (val location = finding.location) {
                null, is Location.CommitRef -> finding
                is Location.FileRef -> validateFileRef(finding, location, headPaths, addedLinesByPath, ruleId, strict, diagnostics)
            }
        }
    }

    private fun validateFileRef(
        finding: Finding,
        location: Location.FileRef,
        headPaths: Set<String>,
        addedLinesByPath: Map<String, Map<Int, String>?>,
        ruleId: String,
        strict: Boolean,
        diagnostics: ContextQualityDiagnostics?,
    ): Finding? {
        val dropRule = finding.rule.ifBlank { ruleId }
        if (location.path !in headPaths) {
            diagnostics?.findingDropped(
                ruleId = dropRule,
                path = location.path,
                line = location.line,
                reason = FindingDropReason.PATH_UNKNOWN,
            )
            return if (strict) null else finding
        }

        val line = location.line ?: return finding
        val added = addedLinesByPath[location.path] ?: return finding
        val resolved = if (strict) AnchorResolver.resolve(finding, added) else finding
        if (resolved == null || (!strict && line !in added)) {
            diagnostics?.findingDropped(ruleId = dropRule, path = location.path, line = line, reason = FindingDropReason.NOT_CHANGED)
        }
        return resolved
    }
}
