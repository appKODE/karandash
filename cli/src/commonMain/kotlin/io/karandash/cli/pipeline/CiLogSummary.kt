package io.karandash.cli.pipeline

import io.karandash.cli.commands.validate.ResolvedFormatOutput
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.sdk.Logger
import io.karandash.utils.mr.shortSha
import io.karandash.utils.takeWhole

/**
 * The always-on CI log summary: a compact findings list and one `Result:` line, at info so `--quiet` hides it.
 *
 * The list is left out when an interactive format (the console report) already printed the findings.
 */
internal object CiLogSummary {
    const val MAX_LISTED = 20
    private const val MESSAGE_CHARS = 120

    fun log(
        findings: List<Finding>,
        exitCode: Int,
        pairs: List<ResolvedFormatOutput>,
        logger: Logger,
    ) {
        val consoleShown = pairs.any { it.formatModule.isDefaultInteractive }
        val platformNote = pairs.any { it.outputModule.requiresPlatformContext }
        lines(findings, exitCode, listFindings = !consoleShown, platformNote = platformNote).forEach(logger::info)
    }

    fun lines(
        findings: List<Finding>,
        exitCode: Int,
        listFindings: Boolean,
        platformNote: Boolean,
    ): List<String> =
        buildList {
            if (listFindings && findings.isNotEmpty()) {
                add("Findings (${findings.size}):")
                findings.sortedBy { it.severity.ordinal }.take(MAX_LISTED).forEach { add("  ${line(it)}") }
                if (findings.size > MAX_LISTED) add("  +${findings.size - MAX_LISTED} more")
            }
            add(resultLine(findings, exitCode, platformNote))
        }

    fun resultLine(
        findings: List<Finding>,
        exitCode: Int,
        platformNote: Boolean,
    ): String {
        val status = if (exitCode == ExitCodes.SUCCESS) "PASSED" else "FAILED"
        val counts = listOf(Severity.ERROR to "error", Severity.WARNING to "warning", Severity.INFO to "info")
            .mapNotNull { (severity, noun) ->
                findings.count { it.severity == severity }.takeIf { it > 0 }?.let { "$it $noun${if (it == 1) "" else "s"}" }
            }
        val detail = counts.ifEmpty { listOf("no findings") }.joinToString(", ")
        val note = if (platformNote && findings.isNotEmpty()) " (details in the MR note)" else ""
        return "Result: $status — $detail$note"
    }

    private fun line(finding: Finding): String {
        val location = when (val loc = finding.location) {
            is Location.FileRef -> " [${loc.path}${loc.line?.let { ":$it" }.orEmpty()}]"
            is Location.CommitRef -> " [${loc.sha.shortSha()}]"
            null -> ""
        }
        val first = finding.message.lineSequence().first()
        val short = first.takeWhole(MESSAGE_CHARS).let { if (it.length < finding.message.length) "$it…" else it }
        return "${finding.severity.name.lowercase()} ${finding.rule}$location: $short"
    }
}
