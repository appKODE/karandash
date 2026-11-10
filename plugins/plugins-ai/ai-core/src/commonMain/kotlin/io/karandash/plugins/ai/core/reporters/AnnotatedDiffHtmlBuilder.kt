package io.karandash.plugins.ai.core.reporters

import io.karandash.helpers.reporter.escapeHtml
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity

/**
 * Builds a self-contained HTML page showing the MR diff with inline annotations.
 */
object AnnotatedDiffHtmlBuilder {
    /**
     * Renders a complete HTML document with per-file diff sections and inline finding annotations.
     *
     * @param mr The merge request providing diff and metadata.
     * @param findings Findings to annotate; only [Location.FileRef] findings are shown inline.
     * @param language `report.language` of the fixed texts; null is English.
     * @return Self-contained HTML string with embedded CSS.
     */
    fun build(
        mr: MergeRequest,
        findings: List<Finding>,
        language: String? = null,
    ): String {
        val findingsByFile = findings
            .filter { it.location is Location.FileRef }
            .groupBy { (it.location as Location.FileRef).path }

        return buildString {
            appendLine("<!DOCTYPE html><html><head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<title>${AiReportText.DIFF_TITLE.of(language, mr.title.escapeHtml())}</title>")
            appendLine("<style>")
            appendLine(CSS)
            appendLine("</style></head><body>")
            appendLine("<h1>${mr.title.escapeHtml()}</h1>")
            val count = AiReportText.DIFF_FINDINGS.of(language, findings.size)
            appendLine("<p>${mr.sourceBranch.escapeHtml()} → ${mr.targetBranch.escapeHtml()} | $count</p>")

            for (change in mr.changes) {
                val fileFindings = findingsByFile[change.path] ?: emptyList()
                appendLine("<div class=\"file\"><h2>${change.path.escapeHtml()}</h2>")
                val diffContent = change.diffContent
                if (diffContent != null) {
                    appendLine("<pre class=\"diff\">")
                    appendLine(diffContent.escapeHtml())
                    appendLine("</pre>")
                }
                for (f in fileFindings) {
                    val cls = severityClass(f.severity)
                    val line = (f.location as? Location.FileRef)?.line?.let { "L$it: " } ?: ""
                    appendLine("<div class=\"annotation $cls\">$line${f.message.escapeHtml()}</div>")
                }
                appendLine("</div>")
            }

            appendLine("</body></html>")
        }
    }

    private fun severityClass(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> CSS_CLASS_ERROR
            Severity.WARNING -> CSS_CLASS_WARNING
            Severity.INFO -> CSS_CLASS_INFO
        }

    private const val CSS_CLASS_ERROR = "error"
    private const val CSS_CLASS_WARNING = "warning"
    private const val CSS_CLASS_INFO = "info"

    private val CSS = """
body { font-family: -apple-system, sans-serif; margin: 20px; }
.file { margin: 20px 0; border: 1px solid #ddd; border-radius: 4px; }
.file h2 { padding: 10px; background: #f6f8fa; margin: 0; font-size: 14px; }
.diff { margin: 0; padding: 10px; overflow-x: auto; font-size: 12px; background: #fafafa; }
.annotation { padding: 8px 12px; border-left: 3px solid; margin: 2px 0; font-size: 13px; }
.error { border-color: #d73a49; background: #ffeef0; }
.warning { border-color: #e36209; background: #fff8e1; }
.info { border-color: #0366d6; background: #f1f8ff; }
    """.trimIndent()
}
