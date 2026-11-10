package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.utils.GlobMatcher
import io.karandash.utils.finding.FindingFingerprint

/**
 * Post-validation filter pipeline for AI-generated findings.
 *
 * Applies the following stages in order:
 * 1. Remove findings on deleted files (no location path)
 * 2. Deduplicate findings with the same rule + file + line (keep highest severity)
 * 3. Remove low-confidence AI findings below the threshold
 * 4. Cap findings per file
 * 5. Cap findings per rule
 *
 * Only AI findings ([isAiFinding]) are subject to confidence and cap filtering.
 * Non-AI findings pass through unchanged.
 */
class AiFindingFilter(
    private val config: FilterConfig,
    private val logger: Logger = NoOpLogger,
    private val ranking: RankingConfig = RankingConfig(),
) {
    /**
     * Filters the given findings according to the configured rules.
     *
     * @param findings All validation findings (AI and non-AI)
     * @param deletedFiles Set of file paths that were deleted in the MR diff
     * @param changedFiles Set of all file paths present in the MR diff (for evidence anchoring)
     * @param diffLineRanges Map of file path to set of line numbers present in the diff (for line validation)
     * @return Filtered findings list
     */
    fun filter(
        findings: List<Finding>,
        deletedFiles: Set<String> = emptySet(),
        changedFiles: Set<String> = emptySet(),
        diffLineRanges: Map<String, Set<Int>> = emptyMap(),
    ): List<Finding> = filterWithRecords(findings, deletedFiles, changedFiles, diffLineRanges).findings

    /** [filter], with the per-file and per-rule cap decisions of every finding (`ai/stages/ranking.json`). */
    fun filterWithRecords(
        findings: List<Finding>,
        deletedFiles: Set<String> = emptySet(),
        changedFiles: Set<String> = emptySet(),
        diffLineRanges: Map<String, Set<Int>> = emptyMap(),
    ): StageResult {
        var result = findings
        val records = mutableListOf<StageRecord>()

        if (deletedFiles.isNotEmpty()) {
            result = removeDeletedFileFindings(result, deletedFiles)
        }

        if (config.anchored && changedFiles.isNotEmpty()) {
            result = anchorToChangedFiles(result, changedFiles)
        }

        if (config.validateDiffLines && diffLineRanges.isNotEmpty()) {
            result = validateLineNumbers(result, diffLineRanges)
        }

        if (config.deduplicate) {
            result = deduplicate(result)
        }

        result = removeByConfidence(result)
        result = capPerFile(result, records)
        result = capPerRule(result, records)

        if (!config.confidenceMetadata) {
            result = result.map { f ->
                if (f.isAiFinding()) f.copy(metadata = f.metadata - AiFindingMetadataKeys.CONFIDENCE) else f
            }
        }
        return StageResult(result, records)
    }

    /**
     * Removes findings that reference files which were deleted in the diff.
     */
    private fun removeDeletedFileFindings(
        findings: List<Finding>,
        deletedFiles: Set<String>,
    ): List<Finding> =
        findings.filter { finding ->
            val loc = finding.location as? Location.FileRef ?: return@filter true
            loc.path !in deletedFiles
        }

    /**
     * Deduplicates AI findings by semantic fingerprint.
     * When duplicates exist, keeps the one with the highest severity (lowest ordinal).
     * Non-AI findings pass through unchanged, and every survivor keeps its place in the input order.
     */
    private fun deduplicate(findings: List<Finding>): List<Finding> {
        val ai = findings.withIndex().filter { it.value.isAiFinding() }
        val keep = ai
            .groupBy { dedupKey(it.value) }
            .values
            .mapTo(mutableSetOf()) { group -> group.minBy { it.value.severity.ordinal }.index }
        val duplicateCount = ai.size - keep.size
        if (duplicateCount > 0) {
            logger.debug("AI filter [dedup]: $duplicateCount duplicate(s) removed from ${ai.size} AI finding(s)")
        }
        return findings.filterIndexed { index, finding -> !finding.isAiFinding() || index in keep }
    }

    private fun dedupKey(finding: Finding): String {
        val loc = finding.location as? Location.FileRef
        return when (config.deduplicateKey) {
            FilterConfig.DEDUP_KEY_RULE_FILE_LINE -> "${finding.rule}:${loc?.path}:${loc?.line}"
            FilterConfig.DEDUP_KEY_RULE_FILE -> "${finding.rule}:${loc?.path}"
            else -> finding.fingerprint ?: FindingFingerprint.generate(finding.rule, finding.location, finding.message)
        }
    }

    /**
     * Removes AI findings whose confidence metadata is below the threshold.
     * Non-AI findings and AI findings without confidence metadata are kept.
     *
     * A code suggestion of a run with review comments is judged by the confidence of the comment it pairs with
     * ([AiMetadataTransformer.pairsWith]); with no such comment it fails, it would be a fix for a dropped remark.
     */
    private fun removeByConfidence(findings: List<Finding>): List<Finding> {
        var dropped = 0
        var withoutMeta = 0
        val total = findings.size
        val comments = findings.filter { it.rule == AiRuleIds.REVIEW_COMMENTS && it.location != null }
        val result = findings.filter { finding ->
            if (!finding.isAiFinding()) return@filter true
            val judgedByTwin = finding.rule == AiRuleIds.CODE_SUGGESTIONS && comments.isNotEmpty()
            val twin = if (judgedByTwin) comments.firstOrNull { AiMetadataTransformer.pairsWith(finding, it) } else null
            if (judgedByTwin && twin == null) {
                dropped++
                logger.debug("  dropped [${finding.rule}] with no review comment to pair with")
                return@filter false
            }
            val confidence = (twin ?: finding).metadata[AiFindingMetadataKeys.CONFIDENCE]?.toDoubleOrNull()
            if (confidence == null) {
                withoutMeta++
                return@filter true
            }
            val threshold = thresholdFor(finding)
            if (confidence < threshold) {
                dropped++
                logger.debug(
                    "  dropped [${finding.rule}] ${finding.severity} confidence=$confidence" +
                        " < $threshold — ${finding.message.take(LOG_MSG_LIMIT)}",
                )
                return@filter false
            }
            true
        }
        val kept = total - dropped
        if (dropped > 0 || withoutMeta > 0) {
            logger.debug(
                "AI filter [confidence]: kept $kept/$total, dropped $dropped ($withoutMeta had no confidence)",
            )
        }
        return result
    }

    private fun thresholdFor(finding: Finding): Double {
        val model = finding.metadata[WellKnownMetadataKeys.AI_MODEL] ?: return config.minConfidence
        return config.minConfidenceByModel.entries
            .firstOrNull { (pattern, _) -> GlobMatcher.matches(model, pattern) }
            ?.value ?: config.minConfidence
    }

    /**
     * Caps AI findings per file by [FindingCap]; non-AI findings and AI findings without a file are not counted.
     */
    private fun capPerFile(
        findings: List<Finding>,
        records: MutableList<StageRecord>,
    ): List<Finding> {
        val capped = capByGroup(findings, config.maxPerFile, records) { finding ->
            (finding.location as? Location.FileRef)?.path?.takeIf { finding.isAiFinding() }
        }
        val dropped = findings.size - capped.size
        if (dropped > 0) {
            logger.debug("AI filter [per-file cap]: $dropped finding(s) exceeded max ${config.maxPerFile}/file")
        }
        return capped
    }

    /**
     * Caps AI findings per rule by [FindingCap].
     * Non-AI findings are not counted against the cap.
     */
    private fun capPerRule(
        findings: List<Finding>,
        records: MutableList<StageRecord>,
    ): List<Finding> {
        val capped = capByGroup(findings, config.maxPerRule, records) { finding -> finding.rule.takeIf { finding.isAiFinding() } }
        val dropped = findings.size - capped.size
        if (dropped > 0) {
            logger.debug("AI filter [per-rule cap]: $dropped finding(s) exceeded max ${config.maxPerRule}/rule")
        }
        return capped
    }

    // [FindingCap]: a BUG/SEC/DATA/LEAK finding below the cut becomes INFO; a code-suggestion twin of a review
    // comment is not counted, it shares its comment's fate. A finding without a group (null key) passes, and every
    // survivor keeps its place in the input order.
    private fun capByGroup(
        findings: List<Finding>,
        maxPerGroup: Int,
        records: MutableList<StageRecord>,
        groupKey: (Finding) -> String?,
    ): List<Finding> {
        val keys = findings.map(groupKey)
        val out = findings.mapIndexed { i, f -> f.takeIf { keys[i] == null } }.toMutableList()
        findings.indices.filter { keys[it] != null }.groupBy { keys[it] }.values.forEach { group ->
            val result = FindingCap.keep(group.map { findings[it] }, maxPerGroup, ranking = ranking)
            records += result.records
            val kept = result.findings.iterator()
            group.zip(result.records).forEach { (index, record) -> if (record.severityAfter != null) out[index] = kept.next() }
        }
        return out.filterNotNull()
    }

    /**
     * Evidence anchoring: removes AI findings that reference files not present in the MR diff,
     * or downgrades them to INFO when [FilterConfig.requireFileInDiff] is false.
     * Non-AI findings and findings without a file location are kept.
     */
    private fun anchorToChangedFiles(
        findings: List<Finding>,
        changedFiles: Set<String>,
    ): List<Finding> {
        var outside = 0
        val result = findings.mapNotNull { finding ->
            val loc = finding.location as? Location.FileRef
            if (!finding.isAiFinding() || loc == null || loc.path in changedFiles) return@mapNotNull finding
            outside++
            if (config.requireFileInDiff) null else downgrade(finding, "[${loc.path} not in diff]")
        }
        if (outside > 0) {
            val action = if (config.requireFileInDiff) "dropped" else "downgraded"
            logger.debug("AI filter [anchor]: $action $outside finding(s) referencing files not in MR diff")
        }
        return result
    }

    /**
     * Line-number validation: drops AI findings whose line number falls outside the actual
     * added-line ranges for that file.
     *
     * With [FilterConfig.downgradeInvalidLines] they are downgraded to INFO with a
     * `"[line X not in diff]"` prefix instead.
     *
     * Downgrading would still post an inline comment on a wrong line, so dropping is the default: the summary comment
     * still contains the finding
     * body in most reporter configurations, so nothing is truly lost, and the platform
     * cannot place an inline comment on a line the model never actually saw.
     *
     * Non-AI findings, findings without a file location, and findings without a line number
     * are kept unchanged.
     */
    private fun validateLineNumbers(
        findings: List<Finding>,
        diffLineRanges: Map<String, Set<Int>>,
    ): List<Finding> {
        var invalid = 0
        val action = if (config.downgradeInvalidLines) "downgraded" else "dropped"
        val result = findings.mapNotNull { finding ->
            val loc = finding.location as? Location.FileRef
            val line = loc?.line
            val validLines = loc?.let { diffLineRanges[it.path] }
            if (!finding.isAiFinding() || line == null || validLines == null || line in validLines) {
                return@mapNotNull finding
            }
            invalid++
            logger.debug(
                "AI filter [line-validation]: $action [${finding.rule}] ${loc.path}:$line " +
                    "(not in diff) — ${finding.message.take(LOG_MSG_LIMIT)}",
            )
            if (config.downgradeInvalidLines) downgrade(finding, "[line $line not in diff]") else null
        }
        if (invalid > 0) {
            logger.debug("AI filter [line-validation]: $action $invalid finding(s) with lines not in diff")
        }
        return result
    }

    companion object {
        private const val LOG_MSG_LIMIT = 80

        private fun downgrade(
            finding: Finding,
            note: String,
        ): Finding = finding.copy(severity = Severity.INFO, message = "$note ${finding.message}")
    }
}
