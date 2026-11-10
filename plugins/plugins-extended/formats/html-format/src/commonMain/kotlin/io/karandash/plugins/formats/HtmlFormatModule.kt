package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.helpers.reporter.ReportMediaKeys
import io.karandash.helpers.reporter.ReportViewOptions
import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.helpers.reporter.displayName
import io.karandash.helpers.reporter.escapeHtml
import io.karandash.helpers.reporter.facts
import io.karandash.helpers.reporter.toRangeDisplayString
import io.karandash.helpers.reporter.toView
import io.karandash.helpers.reporter.verdictLabel
import io.karandash.models.finding.FIX_HINT_METADATA_KEYS
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.finding.fixHintLines
import io.karandash.models.report.MrView
import io.karandash.models.report.ReportStyle
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.PluginContext
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Config for [HtmlFormatModule]: the fix-hint [language] the pipeline takes from `report.language`, the `report.style`
 * preset ([style]; `verbose` keeps the full table) and the merge request [mr] the pipeline adds for the link and subject.
 * [template] (usually `{ file: ... }`) and [partials] replace the bundled page of the `standard` and `quiet` styles;
 * `{{x}}` is HTML-escaped, and a broken template falls back to the bundled one (warn).
 */
@Serializable
data class HtmlFormatConfig(
    val language: String? = null,
    val style: String? = null,
    val mr: MrView? = null,
    val template: String? = null,
    val partials: Map<String, String> = emptyMap(),
) : ReporterConfig

/**
 * Pure formatter that converts a [ValidationReport] into an HTML document.
 *
 * Extracts the HTML generation logic from `EmailReporterModule` / `EmailReportHtmlBuilder`.
 * No I/O is performed -- the resulting [FormattedReport.body] is delivered by an output module.
 */
class HtmlFormatModule : AbstractTypedFormatModule<HtmlFormatConfig>(HtmlFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "HTML Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results as an HTML document."

    private var logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.html"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "html"
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: HtmlFormatConfig,
    ): FormattedReport {
        val style = ReportStyle.of(config.style)
        val view = report.toView(ReportViewOptions(language = config.language, mr = config.mr, style = style))
        if (style.legacyLayout) {
            val body = buildHtml(report.findings, report.passed, config.language)
            return FormattedReport(formatId = FORMAT_ID, body = body, metadata = mapOf(ReportMediaKeys.SUBJECT to emailSubject(view)))
        }
        val (body, metadata) = compactHtml(view, TemplateOverride(config.template, config.partials), logger)
        return FormattedReport(formatId = FORMAT_ID, body = body, metadata = metadata)
    }

    private fun buildHtml(
        findings: List<Finding>,
        passed: Boolean,
        language: String?,
    ): String {
        val statusColor = if (passed) "#28a745" else "#dc3545"
        val statusText = verdictLabel(passed, language)

        val summary = findings.summarize()

        return buildString {
            appendHtmlHeader(statusColor, statusText, summary.errors, summary.warnings, summary.info, findings.size, language)
            if (findings.isEmpty()) {
                appendPassedBody(language)
            } else {
                appendFindingsTable(findings, language)
            }
            appendHtmlFooter(language)
        }
    }

    private fun StringBuilder.appendHtmlHeader(
        statusColor: String,
        statusText: String,
        errors: Int,
        warnings: Int,
        info: Int,
        total: Int,
        language: String?,
    ) {
        append(
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 800px; margin: 0 auto; padding: 20px; }
                    .header { background-color: $statusColor; color: white; padding: 20px; border-radius: 5px; margin-bottom: 20px; }
                    .header h1 { margin: 0; font-size: 24px; }
                    .summary { background-color: #f8f9fa; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
                    .summary p { margin: 5px 0; }
                    .findings { margin-bottom: 20px; }
                    .footer { margin-top: 30px; padding-top: 20px; border-top: 1px solid #ddd; font-size: 0.9em; color: #666; }
                    table { width: 100%; border-collapse: collapse; }
                    th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
                    th { background-color: #f8f9fa; font-weight: bold; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>${HtmlText.TITLE.of(language, statusText)}</h1>
                    </div>

                    <div class="summary">
                        <p><strong>${HtmlText.SUMMARY.of(language)}</strong></p>
                        <p>${HtmlText.COUNTS.of(language, errors, warnings, info)}</p>
                        <p>${HtmlText.TOTAL.of(language, total)}</p>
                    </div>
            """.trimIndent(),
        )
    }

    private fun StringBuilder.appendPassedBody(language: String?) {
        append(
            """
                <div class="findings">
                    <p>&#x2705; ${HtmlText.ALL_PASSED.of(language)}</p>
                </div>
            """.trimIndent(),
        )
    }

    private fun StringBuilder.appendFindingsTable(
        findings: List<Finding>,
        language: String?,
    ) {
        append(
            """
                <div class="findings">
                    <h2>${HtmlText.FINDINGS.of(language)}</h2>
                    <table>
                        <thead>
                            <tr>
                                <th>${HtmlText.SEVERITY.of(language)}</th>
                                <th>${HtmlText.RULE.of(language)}</th>
                                <th>${HtmlText.MESSAGE.of(language)}</th>
                                <th>${HtmlText.LOCATION.of(language)}</th>
                            </tr>
                        </thead>
                        <tbody>
            """.trimIndent(),
        )
        findings.forEach { finding -> append(buildFindingRow(finding, language)) }
        append(
            """
                        </tbody>
                    </table>
                </div>
            """.trimIndent(),
        )
    }

    private fun StringBuilder.appendHtmlFooter(language: String?) {
        append(
            """
                    <div class="footer">
                        <p>${HtmlText.GENERATED.of(language)}</p>
                        <p>${HtmlText.TIMESTAMP.of(language, Clock.System.now())}</p>
                    </div>
                </div>
            </body>
            </html>
            """.trimIndent(),
        )
    }

    private fun buildFindingRow(
        finding: Finding,
        language: String?,
    ): String {
        val severityColor = when (finding.severity) {
            Severity.ERROR -> "#dc3545"
            Severity.WARNING -> "#ffc107"
            Severity.INFO -> "#17a2b8"
        }
        val location = finding.location?.toRangeDisplayString() ?: "-"
        val messageCell = buildMessageCell(finding, language)
        return "<tr>" +
            "<td style=\"color: $severityColor; font-weight: bold;\">${HtmlText.severity(finding.severity, language)}</td>" +
            "<td><code>${finding.rule.escapeHtml()}</code></td>" +
            "<td>$messageCell</td>" +
            "<td>${location.escapeHtml()}</td>" +
            "</tr>"
    }

    private fun buildMessageCell(
        finding: Finding,
        language: String?,
    ): String =
        buildString {
            append(finding.message.escapeHtml())
            finding.fixHintLines(language).forEach { append("<br><em>${it.escapeHtml()}</em>") }
            finding.explanation?.let { append("<br><small>${it.escapeHtml()}</small>") }
            val metadata = (finding.metadata - FIX_HINT_METADATA_KEYS).toList() + finding.facts() +
                listOfNotNull(finding.errorCode?.let { "errorCode" to it })
            if (metadata.isNotEmpty()) {
                val metaHtml = metadata
                    .joinToString(", ") { (k, v) -> "<em>${k.escapeHtml()}: ${v.escapeHtml()}</em>" }
                append("<br><small>$metaHtml</small>")
            }
            for (attachment in finding.attachments) {
                append("<br><details><summary>${attachment.displayName.escapeHtml()}</summary>")
                append("<pre>${attachment.content.escapeHtml()}</pre></details>")
            }
        }
}
