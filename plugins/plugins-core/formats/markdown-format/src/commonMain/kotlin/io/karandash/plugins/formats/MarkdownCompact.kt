package io.karandash.plugins.formats

import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ValidationReport
import io.karandash.helpers.reporter.ReportViewOptions
import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.helpers.reporter.hasWhere
import io.karandash.helpers.reporter.renderReportTemplate
import io.karandash.helpers.reporter.toView
import io.karandash.models.report.MrView
import io.karandash.models.report.ReportStyle
import io.karandash.models.template.TemplateEscapes
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonPrimitive

/** Template data key of the pre-rendered AI sections, provenance block and contributor line. */
internal const val MARKDOWN_SECTIONS_KEY = "markdownSections"

/** Template data key: true when the table has at least one row. */
private const val ROWS_KEY = "hasRows"

/** Template data key: true when a row names a file or a commit, so the `Where` column is shown. */
private const val WHERE_KEY = "hasWhere"

/** Template data key: the footer's "react 👎 to mark a false positive" hint in the summary language. */
private const val FEEDBACK_HINT_KEY = "feedbackHint"

/**
 * The bundled MR summary template of the standard and quiet styles: an alert verdict with the headline, one table row
 * per finding (icon, deep link - left out when every row is only `MR` -, rule and finding, how to fix), the collapsed details, the AI sections and the footer.
 * `{{x}}` is Markdown-escaped; `{{{x}}}` is raw. A linked `Where` shows the short name with the full path as the link
 * title; a row on a file line ends with a hidden `<!-- karandash-row <fp> -->` naming its finding, so a platform
 * output can drop the rows its inline threads already show.
 */
internal val MARKDOWN_SUMMARY_TEMPLATE: String =
    """
    |{{^passed}}
    |> [!caution]
    |{{/passed}}
    |{{#passed}}
    |> [!tip]
    |{{/passed}}
    |> **{{headline}}**
    |{{#hasRows}}
    |
    || |{{#hasWhere}} {{labels.where}} |{{/hasWhere}} {{labels.rule}} | {{labels.finding}} | {{labels.fix}} |
    ||---|{{#hasWhere}}---|{{/hasWhere}}---|---|---|
    |{{/hasRows}}
    |{{#findings}}
    || {{icon}} |{{#hasWhere}} {{#link}}[{{shortWhere}}]({{{link}}} "{{path}}"){{/link}}{{^link}}{{where}}{{/link}} |{{/hasWhere}} `{{{rule}}}` | {{message}} | {{fix}}{{#fingerprint}} ${InlineFindingMarker.ROW_PREFIX}{{{fingerprint}}} -->{{/fingerprint}} |
    |{{/findings}}
    |{{#more}}
    |
    |_+{{more}} {{labels.more}}_
    |{{/more}}
    |{{#details.any}}
    |
    |<details><summary>{{labels.details}}</summary>
    |
    |{{#details.lines}}
    |- {{.}}
    |{{/details.lines}}
    |
    |</details>
    |{{/details.any}}
    |{{#markdownSections}}
    |
    |{{{markdownSections}}}
    |{{/markdownSections}}
    |
    |<sub>{{footer}} · {{feedbackHint}}</sub>
    |
    """.trimMargin()

/**
 * What [formatCompactSummary] renders besides the report.
 *
 * Not @Serializable: carries a live [Logger].
 */
internal data class CompactSummaryInput(
    val style: ReportStyle,
    val locale: SummaryLocale,
    val mr: MrView?,
    val sections: String,
    val override: TemplateOverride = TemplateOverride(),
    val logger: Logger? = null,
)

/**
 * The compact MR summary within [SUMMARY_MAX_BYTES]: every finding row first; over the limit, the rows are halved
 * until it fits, then the same without [CompactSummaryInput.sections]; a summary still too large keeps whole lines.
 */
internal fun formatCompactSummary(
    report: ValidationReport,
    input: CompactSummaryInput,
): String {
    fun render(
        maxFindings: Int,
        sections: String,
    ): String {
        val options = ReportViewOptions(language = input.locale.tag, mr = input.mr, maxFindings = maxFindings, style = input.style)
        val view = report.toView(options)
        return renderReportTemplate(
            bundled = MARKDOWN_SUMMARY_TEMPLATE,
            view = view,
            escape = TemplateEscapes::markdown,
            override = input.override,
            extra = mapOf(
                MARKDOWN_SECTIONS_KEY to JsonPrimitive(sections),
                ROWS_KEY to JsonPrimitive(view.findings.isNotEmpty()),
                WHERE_KEY to JsonPrimitive(view.hasWhere()),
                FEEDBACK_HINT_KEY to JsonPrimitive(input.locale.t(SummaryText.FEEDBACK_HINT)),
            ),
            logger = input.logger,
        )
    }
    val rows = report.toView(ReportViewOptions(style = input.style)).findings.size
    val limits = generateSequence(rows) { if (it == 0) null else it / 2 }.toList()
    return (limits.asSequence().map { render(it, input.sections) } + limits.asSequence().map { render(it, "") })
        .firstOrNull { it.encodeToByteArray().size <= SUMMARY_MAX_BYTES }
        ?: render(0, "")
            .lineSequence()
            .runningFold("") { acc, line -> "$acc$line\n" }
            .takeWhile { it.encodeToByteArray().size <= SUMMARY_MAX_BYTES }
            .last()
}
