package io.karandash.plugins.formats

import io.karandash.helpers.reporter.skippedRulesText
import io.karandash.models.report.FindingView
import io.karandash.models.report.ReportView

private const val ANSI_RESET = "\u001b[0m"
private const val ANSI_RED = "\u001b[31m"
private const val ANSI_GREEN = "\u001b[32m"
private const val ANSI_YELLOW = "\u001b[33m"
private const val ANSI_CYAN = "\u001b[36m"
private const val ANSI_DIM = "\u001b[2m"
private const val ANSI_BOLD = "\u001b[1m"
private const val TAG_WIDTH = 6
private const val LINE_WIDTH = 5
private const val WHY_MAX_CHARS = 200

private fun paint(
    text: String,
    code: String,
    color: Boolean,
): String = if (color) "$code$text$ANSI_RESET" else text

private fun FindingView.ansi(): String =
    when {
        error -> ANSI_RED
        warning -> ANSI_YELLOW
        else -> ANSI_CYAN
    }

/**
 * The concise console finding list: one block per file (a commit or the merge request for findings without one), one line
 * per finding with the severity, line, message and rule, then the fix and why (one line each, why capped) when there are any.
 */
internal fun StringBuilder.appendConciseFindings(
    view: ReportView,
    color: Boolean,
) {
    for ((name, findings) in view.findings.groupBy { it.path ?: it.where }) {
        appendLine(paint(name, ANSI_BOLD, color))
        for (finding in findings) {
            val tag = paint(finding.tag.padEnd(TAG_WIDTH), finding.ansi(), color)
            val line = finding.line
                ?.toString()
                .orEmpty()
                .padStart(LINE_WIDTH)
            appendLine("  $tag $line  ${finding.message}  ${paint(finding.rule, ANSI_DIM, color)}")
            val indent = "  ${" ".repeat(TAG_WIDTH + LINE_WIDTH + 1)}  "
            finding.fix?.takeIf { it.isNotBlank() }?.let { appendLine("$indent${view.labels.fix}: $it") }
            finding.why?.takeIf { it.isNotBlank() }?.let { appendLine("$indent${view.labels.why}: ${it.take(WHY_MAX_CHARS)}") }
        }
        appendLine()
    }
}

/** The last line of a concise run: `Validation FAILED · 3 errors · 1 warning - 2 new since last run · 1 rule skipped`. */
internal fun conciseStatusLine(
    view: ReportView,
    skipped: Int,
    color: Boolean,
    language: String?,
): String {
    val verdict = paint(ConsoleText.VALIDATION.of(language, view.verdict.uppercase()), if (view.passed) ANSI_GREEN else ANSI_RED, color)
    val skippedText = if (skipped == 0) null else skippedRulesText(skipped, language)
    return listOfNotNull(verdict, view.counts.text.ifEmpty { null }).joinToString(" · ") +
        view.delta
            ?.text
            ?.takeIf { it.isNotEmpty() }
            ?.let { " — $it" }
            .orEmpty() +
        skippedText?.let { " · $it" }.orEmpty()
}
