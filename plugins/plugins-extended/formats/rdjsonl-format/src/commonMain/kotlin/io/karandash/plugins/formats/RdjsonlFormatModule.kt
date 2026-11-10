package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.helpers.reporter.RdDiagnostic
import io.karandash.helpers.reporter.ReviewdogFormatConfig
import io.karandash.helpers.reporter.toRdDiagnostics
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.typed.AbstractTypedFormatModule

/**
 * Pure formatter that converts a [ValidationReport] into Reviewdog JSONL (rdjsonl) format.
 *
 * Unlike rdjson, rdjsonl has no envelope: one bare [RdDiagnostic] JSON object per line, newline
 * delimited. No I/O is performed -- the resulting [FormattedReport.body] is delivered by an
 * output module.
 */
class RdjsonlFormatModule : AbstractTypedFormatModule<ReviewdogFormatConfig>(ReviewdogFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "RDJSONL Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results in Reviewdog JSONL format (rdjsonl)."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.rdjsonl"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "rdjsonl"
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: ReviewdogFormatConfig,
    ): FormattedReport {
        // One diagnostic per line, newline-terminated so the stream concatenates; an empty report is an empty body.
        val body = report
            .toRdDiagnostics(config.language)
            .joinToString("") { KarandashJson.Plain.encodeToString(RdDiagnostic.serializer(), it) + "\n" }
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }
}
