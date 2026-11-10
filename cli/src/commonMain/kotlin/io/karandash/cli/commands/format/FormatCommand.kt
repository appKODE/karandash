package io.karandash.cli.commands.format

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import io.karandash.Version
import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.helpers.reporter.ReportViewOptions
import io.karandash.helpers.reporter.toTemplateData
import io.karandash.helpers.reporter.toView
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.report.MrView
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonObject

/**
 * Top-level `format` command: tools for writing report templates.
 *
 * Usage:
 * ```
 * karandash format schema
 * karandash format render --template summary.mustache --escape md
 * ```
 */
class FormatCommand : CliktCommand(name = "format") {
    override fun help(context: Context) = "Report template tools"

    override fun run() = Unit
}

/** Prints the data a report template sees, filled from a built-in example report: the schema by example. */
class FormatSchemaCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : CliCommandBase("schema", bootstrap) {
    override fun help(context: Context) = "Print the data a report template sees, for an example report"

    override suspend fun execute(): Int {
        echo(KarandashJson.Pretty.encodeToString(JsonObject.serializer(), exampleTemplateData()))
        return ExitCodes.SUCCESS
    }
}

/** The template data of a small failing example report: one finding of each severity, a delta and a merge request. */
internal fun exampleTemplateData(): JsonObject {
    val findings = listOf(
        Finding(Severity.ERROR, "Title is too short", "title-length", suggestion = "Use at least 10 characters"),
        Finding(
            Severity.WARNING,
            "Debug log left in",
            "no-debug-log",
            location = Location.FileRef("src/App.kt", EXAMPLE_LINE),
            explanation = "Debug output leaks into production logs.",
        ),
        Finding(Severity.INFO, "Consider adding a changelog entry", "changelog"),
    )
    val report = ValidationReport(
        passed = false,
        findings = findings,
        summary = findings.summarize(),
        groupedByLocation = findings.groupBy { LocationKey((it.location as? Location.FileRef)?.path.orEmpty(), null) },
        inlineEligible = findings.filter { it.location is Location.FileRef },
        summaryOnly = emptyList(),
        complianceChecklist = emptyList(),
        explanations = emptyMap(),
        metadata = ReportMetadata(version = Version.VERSION, timestamp = 0L),
        baselineComparison = BaselineComparison(newFindings = 1, resolvedFindings = 2, unchangedFindings = 2),
    )
    val mr = MrView(ref = "!42", title = "Add login screen", url = "https://gitlab.example.com/group/app/-/merge_requests/42")
    return report.toView(ReportViewOptions(mr = mr)).toTemplateData()
}

private const val EXAMPLE_LINE = 12
