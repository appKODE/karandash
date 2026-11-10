package io.karandash.plugins.ai

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.ReviewUnit
import io.karandash.plugins.ai.core.ReviewUnits
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.TestGapConfig
import io.karandash.plugins.ai.dispatch.RuleCoverage
import io.karandash.utils.GlobMatcher

/**
 * `ai.large-mr`: run-level INFO findings without a location, so they reach the summary comment and never an inline
 * note. One names the reviewed and not-reviewed review units (and suggests a split past `max-units`); one names the
 * units that changed production code without a test (`ai.testGap.level`: `mr`, only when the MR changes no test at
 * all; `file`, per unit). The units line is
 * emitted only past `max-units` or when a budget or chunk skip lost files. Deterministic: no model call.
 */
internal object LargeMrSummary {
    const val RULE = "ai-large-mr"
    const val TEST_GAP_RULE = "ai-test-gap"
    private const val LISTED_UNITS = 20
    private val CODE_EXTENSIONS = setOf(
        "kt",
        "java",
        "scala",
        "swift",
        "m",
        "mm",
        "c",
        "cc",
        "cpp",
        "cxx",
        "h",
        "hpp",
        "go",
        "rs",
        "py",
        "js",
        "jsx",
        "ts",
        "tsx",
        "rb",
        "php",
        "cs",
        "dart",
    )

    /**
     * Empty when no AI rule ran ([coverages] empty): there is no review to describe. A file matching
     * [excludePatterns] (the effective `ai.size-policy.exclude.patterns`) is never production code.
     */
    fun findings(
        changes: List<FileChange>,
        coverages: Collection<RuleCoverage>,
        config: AiLargeMrConfig,
        excludePatterns: List<String>,
        testGap: TestGapConfig = TestGapConfig(),
    ): List<Finding> {
        if (coverages.isEmpty()) return emptyList()
        val units = ReviewUnits.group(changes)
        // At MR level a test changed anywhere answers for the whole MR
        val testedMr = testGap.level == TestGapConfig.MR && changes.any { ReviewUnits.isTest(it.path) }
        val gaps = if (config.testGaps && !testedMr) testGaps(units, excludePatterns) else emptyList()
        return listOfNotNull(unitsFinding(units, coverages, config)) + gaps
    }

    private fun unitsFinding(
        units: List<ReviewUnit>,
        coverages: Collection<RuleCoverage>,
        config: AiLargeMrConfig,
    ): Finding? {
        val reviewedFiles = coverages.flatMapTo(mutableSetOf()) { it.reviewed }
        // A unit with no file a rule meant to review (all excluded, or outside the incremental delta) is neither.
        val sent = coverages.flatMapTo(mutableSetOf()) { it.files }
        // Only a budget or chunk skip loses files; a file the analyzer chose not to read is not lost.
        val lost = coverages.flatMapTo(mutableSetOf()) { coverage ->
            coverage.dropped.filter { it in coverage.files } +
                if (coverage.chunksSkipped > 0) coverage.files - coverage.reviewed else emptySet()
        } - reviewedFiles
        val reviewed = units.filter { unit -> unit.changes.any { it.path in reviewedFiles } }
        val notReviewed = (units - reviewed.toSet()).filter { unit -> unit.changes.any { it.path in sent } }
        val oversized = units.size > config.maxUnits
        if (!oversized && lost.isEmpty()) return null
        val split = if (oversized) {
            "Consider splitting this MR: ${units.size} review units is over ai.large-mr.max-units ${config.maxUnits}\n\n"
        } else {
            ""
        }
        return Finding(
            severity = Severity.INFO,
            message = "Large MR: ${units.size} review units, ${reviewed.size} reviewed, ${notReviewed.size} not reviewed",
            rule = RULE,
            explanation = split + listed("Reviewed units", reviewed) + "\n\n" + listed("Not reviewed units", notReviewed),
            metadata = mapOf(
                "units" to units.size.toString(),
                "reviewedUnits" to reviewed.size.toString(),
                "notReviewedUnits" to notReviewed.size.toString(),
            ),
        )
    }

    private fun listed(
        title: String,
        units: List<ReviewUnit>,
    ): String {
        if (units.isEmpty()) return "$title: none"
        val more = if (units.size > LISTED_UNITS) "\n- … and ${units.size - LISTED_UNITS} more" else ""
        return "$title:\n" + units.take(LISTED_UNITS).joinToString("\n") { "- ${it.name}" } + more
    }

    private fun testGaps(
        units: List<ReviewUnit>,
        excludePatterns: List<String>,
    ): List<Finding> {
        val gaps = units.mapNotNull { unit ->
            if (unit.changes.any { ReviewUnits.isTest(it.path) }) return@mapNotNull null
            unit.changes
                .count { isProduction(it, excludePatterns) }
                .takeIf { it > 0 }
                ?.let { unit.name to it }
        }
        if (gaps.isEmpty()) return emptyList()
        val files = gaps.sumOf { it.second }
        // one summary per MR: a finding per directory made test gaps most of a review's findings
        return listOf(
            Finding(
                severity = Severity.INFO,
                message = "Test gap: ${gaps.size} unit(s) change $files production files and no test",
                rule = TEST_GAP_RULE,
                explanation = "Units without a test:\n" +
                    gaps.take(LISTED_UNITS).joinToString("\n") { (unit, count) -> "- $unit ($count production files)" } +
                    if (gaps.size > LISTED_UNITS) "\n- … and ${gaps.size - LISTED_UNITS} more" else "",
                metadata = mapOf(
                    "units" to gaps.size.toString(),
                    "unitNames" to gaps.joinToString(",") { it.first },
                    "productionFiles" to files.toString(),
                ),
            ),
        )
    }

    /** Source code, not a build script (`kts` is not listed) or a TypeScript declaration file (`.d.ts`). */
    private fun isProduction(
        change: FileChange,
        excludePatterns: List<String>,
    ): Boolean =
        change.changeType != ChangeType.DELETED &&
            change.path.substringAfterLast('.', "") in CODE_EXTENSIONS &&
            !change.path.endsWith(".d.ts") &&
            excludePatterns.none { GlobMatcher.matches(change.path, it) }
}
