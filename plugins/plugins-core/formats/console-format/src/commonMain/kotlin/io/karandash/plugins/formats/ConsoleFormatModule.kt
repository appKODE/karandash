package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.errors.KarandashError
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.skipReasonIn
import io.karandash.helpers.reporter.ReportViewOptions
import io.karandash.helpers.reporter.facts
import io.karandash.helpers.reporter.isFindingRow
import io.karandash.helpers.reporter.toRangeDisplayString
import io.karandash.helpers.reporter.toView
import io.karandash.helpers.reporter.verdictLabel
import io.karandash.models.finding.FIX_HINT_METADATA_KEYS
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.codeChange
import io.karandash.models.finding.fixHintLines
import io.karandash.models.finding.inCategory
import io.karandash.models.report.ReportStyle
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val DEFAULT_CHECK_MARK = "\u2713"
private const val DEFAULT_CROSS_MARK = "\u2717"

/**
 * Config for [ConsoleFormatModule].
 *
 * @property quiet When true, only print errors (suppress warnings, info, and passing results).
 * @property verbose When true, print the rule, every metadata key and the format diagram per finding.
 * @property explain When true, a finding's explanation is the rule explanation (report, then plugin config) first.
 * @property successMark Character or string used to indicate success in console output.
 * @property failureMark Character or string used to indicate failure in console output.
 * @property language Report language for the fix-hint labels; injected from `report.language`.
 * @property style `--style concise|full` or `report.style`: `full`/`verbose` (and [verbose] or [explain]) print the
 *   full per-finding layout; anything else prints findings grouped by file with the summary line last.
 * @property color ANSI colours in the concise layout; the CLI sets it only on a terminal without `NO_COLOR`,
 *   `TERM=dumb` or `--no-color`.
 */
@Serializable
data class ConsoleFormatConfig(
    val quiet: Boolean = false,
    val verbose: Boolean = false,
    val explain: Boolean = false,
    val successMark: String = DEFAULT_CHECK_MARK,
    val failureMark: String = DEFAULT_CROSS_MARK,
    val language: String? = null,
    val style: String? = null,
    val color: Boolean = false,
) : ReporterConfig

/**
 * Pure formatter that converts a [ValidationReport] into a console-readable string.
 *
 * No I/O is performed -- the resulting [FormattedReport.body] is printed by an output module.
 */
class ConsoleFormatModule : AbstractTypedFormatModule<ConsoleFormatConfig>(ConsoleFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Console Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Formats validation results as human-readable console text."
    override val isDefaultInteractive: Boolean = true

    private val explanations = atomic<Map<String, String>>(emptyMap())

    override suspend fun initialize(context: PluginContext): Boolean {
        val explanationsObj = (context.pluginConfig as? JsonObject)
            ?.get("explanations") as? JsonObject
        explanations.value = explanationsObj?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: v.toString()
        } ?: emptyMap()
        return true
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ): FormattedReport {
        val body = buildConsoleOutput(report, config)
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }

    private fun buildConsoleOutput(
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ): String {
        val sb = StringBuilder()
        val style = ReportStyle.of(config.style)
        when {
            config.quiet -> appendQuietOutput(sb, report, config)
            config.verbose || config.explain || style.legacyLayout || report.findings.isEmpty() -> appendFullOutput(sb, report, config)
            else -> appendConciseOutput(sb, report, config, style)
        }
        return sb.toString()
    }

    /** Findings grouped by file, then the AI items, MR summary, sections and policies, and the status line last. */
    private fun appendConciseOutput(
        sb: StringBuilder,
        report: ValidationReport,
        config: ConsoleFormatConfig,
        style: ReportStyle,
    ) {
        val view = report.toView(ReportViewOptions(language = config.language, style = style))
        sb.appendConciseFindings(view, config.color)
        val advisories = report.findings.filter {
            !it.isFindingRow() && it.metadata[WellKnownMetadataKeys.IS_MR_SUMMARY] != "true" && PresentationKeys.SECTION !in it.metadata
        }
        if (advisories.isNotEmpty()) {
            appendFindingsSection(
                sb,
                ConsoleText.AI_REVIEW.of(config.language),
                advisories.map(style::apply),
                config,
                report.explanations
            )
        }
        appendMrSummaryFindings(sb, report, config.language)
        appendPresentationSections(sb, report, config)
        appendPolicySummary(sb, report, config)
        sb.appendLine(conciseStatusLine(view, report.skippedRules.size, config.color, config.language))
    }

    private fun appendQuietOutput(
        sb: StringBuilder,
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ) {
        val errors = report.summary.errorFindings
        if (errors.isNotEmpty()) {
            appendFindingsSection(sb, ConsoleText.ERRORS_TITLE.of(config.language), errors, config, report.explanations)
        }
    }

    private fun appendFullOutput(
        sb: StringBuilder,
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ) {
        if (report.findings.isEmpty()) {
            sb.appendLine(ConsoleText.NO_ISSUES.of(config.language))
            appendPolicySummary(sb, report, config)
            return
        }

        sb.appendLine(ConsoleText.FOUND_ISSUES.count(config.language, report.findings.size))
        sb.appendLine()

        appendErrorsWarningsInfo(sb, report, config)
        appendMrSummaryFindings(sb, report, config.language)
        appendPresentationSections(sb, report, config)
        appendPolicySummary(sb, report, config)
        if (config.verbose) appendCategoryLine(sb, report.findings, config.language)

        sb.appendLine(ConsoleText.VALIDATION.of(config.language, verdictLabel(report.passed, config.language)))
    }

    /** one `Categories:` line with the finding count per [FindingCategory]; absent when no finding has one. */
    private fun appendCategoryLine(
        sb: StringBuilder,
        findings: List<Finding>,
        language: String?,
    ) {
        val counts = FindingCategory.entries.map { it to findings.inCategory(it).size }.filter { it.second > 0 }
        if (counts.isNotEmpty()) {
            sb.appendLine(
                ConsoleText.CATEGORIES.of(language, counts.joinToString(", ") { (category, n) -> "${category.name} $n" })
            )
        }
    }

    private fun appendErrorsWarningsInfo(
        sb: StringBuilder,
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ) {
        val errors = report.summary.errorFindings.filter { it.metadata[PresentationKeys.SECTION] == null }
        if (errors.isNotEmpty()) {
            appendFindingsSection(sb, ConsoleText.ERRORS_TITLE.of(config.language), errors, config, report.explanations)
        }
        val warnings = report.summary.warningFindings.filter { it.metadata[PresentationKeys.SECTION] == null }
        if (warnings.isNotEmpty()) {
            appendFindingsSection(sb, ConsoleText.WARNINGS_TITLE.of(config.language), warnings, config, report.explanations)
        }
        val regularInfo = report.summary.infoFindings.filter {
            it.metadata[WellKnownMetadataKeys.IS_MR_SUMMARY] != "true" &&
                it.metadata[PresentationKeys.SECTION] == null
        }
        if (regularInfo.isNotEmpty()) {
            appendFindingsSection(sb, ConsoleText.INFO_TITLE.of(config.language), regularInfo, config, report.explanations)
        }
    }

    private fun appendMrSummaryFindings(
        sb: StringBuilder,
        report: ValidationReport,
        language: String?,
    ) {
        val mrSummaryFindings = report.summary.infoFindings.filter {
            it.metadata[WellKnownMetadataKeys.IS_MR_SUMMARY] == "true"
        }
        if (mrSummaryFindings.isNotEmpty()) {
            sb.appendLine(ConsoleText.CHANGES_SUMMARY.of(language))
            sb.appendLine()
            mrSummaryFindings.forEach { finding ->
                sb.appendLine(finding.message)
            }
            sb.appendLine()
        }
    }

    /**
     * Findings routed by [PresentationKeys.SECTION] get their own block, like markdown's supplementary
     * sections, headed by [PresentationKeys.SECTION_TITLE] and ordered by [PresentationKeys.SECTION_ORDER].
     */
    private fun appendPresentationSections(
        sb: StringBuilder,
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ) {
        val routed = report.findings.filter { it.metadata[WellKnownMetadataKeys.IS_MR_SUMMARY] != "true" }
        for ((title, findings) in PresentationKeys.sections(routed)) {
            appendFindingsSection(sb, title, findings, config, report.explanations)
        }
    }

    private fun appendPolicySummary(
        sb: StringBuilder,
        report: ValidationReport,
        config: ConsoleFormatConfig,
    ) {
        val policies = report.appliedPolicies
        val skipped = report.skippedRules
        if (policies.isEmpty() && skipped.isEmpty()) return

        val matchedCount = policies.count { it.matched }
        sb.appendLine(ConsoleText.POLICIES.of(config.language, policies.size, matchedCount))
        policies.forEach { policy ->
            val marker = if (policy.matched) config.successMark else config.failureMark
            sb.appendLine("  $marker  ${policy.displayLabel}")
        }
        if (skipped.isNotEmpty()) {
            sb.appendLine(ConsoleText.SKIPPED_RULES.of(config.language, skipped.size))
            for (info in skipped) {
                val reasonSuffix = if (info.reason.isNotEmpty()) " — ${skipReasonIn(info.reason, config.language)}" else ""
                sb.appendLine("  - ${info.ruleId}$reasonSuffix")
            }
        }
        sb.appendLine()
    }

    private fun appendFindingsSection(
        sb: StringBuilder,
        title: String,
        findings: List<Finding>,
        config: ConsoleFormatConfig,
        reportExplanations: Map<String, String>,
    ) {
        sb.appendLine("$title (${findings.size}):")
        findings.forEach { finding ->
            appendSingleFinding(sb, finding, config, reportExplanations)
        }
        sb.appendLine()
    }

    private fun appendSingleFinding(
        sb: StringBuilder,
        finding: Finding,
        config: ConsoleFormatConfig,
        reportExplanations: Map<String, String>,
    ) {
        sb.appendLine("  - [${finding.rule}] ${finding.message}")
        finding.fixHintLines(config.language).forEach { sb.appendLine("    $it") }
        finding.metadata[WellKnownMetadataKeys.FORMAT_EXPLAIN]?.takeIf { it.isNotBlank() }?.let { sb.appendLine("    ${it.trim()}") }
        if (config.verbose) {
            sb.appendLine("    ${ConsoleText.RULE_LABEL.of(config.language)}: ${finding.rule}")
            finding.metadata[WellKnownMetadataKeys.FORMAT_DIAGRAM]?.takeIf { it.isNotBlank() }?.let { diagram ->
                sb.appendLine("    ${ConsoleText.FORMAT_DIAGRAM.of(config.language)}")
                diagram.trimEnd().lines().forEach { sb.appendLine("      $it") }
            }
        }
        appendFindingDetails(sb, finding, config)
        finding.location?.let { location ->
            sb.appendLine("    ${ConsoleText.LOCATION.of(config.language)}: ${location.toRangeDisplayString()}")
        }
        val explanation = if (config.explain) {
            reportExplanations[finding.rule] ?: explanations.value[finding.rule] ?: finding.explanation
        } else {
            finding.explanation
        }
        explanation?.let { sb.appendLine("    ${ConsoleText.EXPLANATION.of(config.language)}: $it") }
        for (attachment in finding.attachments) {
            sb.appendLine("    ${ConsoleText.ATTACHMENT.of(config.language)}: ${attachment.filename}")
            sb.appendLine(attachment.content)
        }
    }

    /**
     * The error code (a catalog code names its title and docs page), the [facts], the metadata (with `verbose`
     * every key, otherwise those not shown elsewhere) and the suggested code change.
     */
    private fun appendFindingDetails(
        sb: StringBuilder,
        finding: Finding,
        config: ConsoleFormatConfig,
    ) {
        val language = config.language
        finding.errorCode?.let { code ->
            val error = KarandashError.fromCode(code)
            val label = ConsoleText.ERROR_LABEL.of(language)
            sb.appendLine(if (error != null) "    $label: ${error.code} ${error.title} (${error.docsUrl})" else "    $label: $code")
        }
        finding.facts().forEach { (key, value) -> sb.appendLine("    $key: $value") }
        val hidden = if (config.verbose) FIX_HINT_METADATA_KEYS else FindingField.RENDERED_METADATA_KEYS
        (finding.metadata - hidden).forEach { (key, value) -> sb.appendLine("    $key: $value") }
        finding.codeChange()?.let { change ->
            sb.appendLine("    ${ConsoleText.SUGGESTED_CHANGE.of(language, change.startLine, change.endLine)}")
            change.suggestedCode
                .trimEnd()
                .lines()
                .forEach { sb.appendLine("      $it") }
        }
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.console"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "console"
    }
}

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # console

    **Console Reporter**

    Enable or disable console output for validation results.

    **Type:** `Boolean`

    ## Example
    ```
    reporters {
        console = true
    }
    ```
""".trimIndent()
