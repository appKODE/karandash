package io.karandash.plugins.formats

import io.karandash.helpers.reporter.ReportMediaKeys
import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.helpers.reporter.hasWhere
import io.karandash.helpers.reporter.renderReportTemplate
import io.karandash.models.report.ReportView
import io.karandash.models.template.TemplateEscapes
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonPrimitive

/** The bundled HTML template of the standard and quiet styles: verdict banner, headline, findings table, details, footer. */
internal val HTML_TEMPLATE: String =
    """
    |<!DOCTYPE html>
    |<html>
    |<head><meta charset="UTF-8"></head>
    |<body style="font-family: -apple-system, Segoe UI, Arial, sans-serif; color: #24292f; line-height: 1.5;">
    |<div style="max-width: 760px; margin: 0 auto; padding: 16px;">
    |<div style="padding: 12px 16px; border-radius: 6px; color: #fff; background: {{#passed}}#1a7f37{{/passed}}{{^passed}}#cf222e{{/passed}};">
    |<strong>{{headline}}</strong>{{#delta.text}}<br>{{delta.text}}{{/delta.text}}
    |</div>
    |{{#mr}}
    |<p>{{#url}}<a href="{{url}}">{{ref}} {{title}}</a>{{/url}}{{^url}}{{ref}} {{title}}{{/url}}</p>
    |{{/mr}}
    |{{#hasRows}}
    |<table style="width: 100%; border-collapse: collapse;">
    |<tr><th></th>{{#hasWhere}}<th style="text-align: left;">{{labels.where}}</th>{{/hasWhere}}<th style="text-align: left;">{{labels.rule}}</th><th style="text-align: left;">{{labels.finding}}</th><th style="text-align: left;">{{labels.fix}}</th></tr>
    |{{/hasRows}}
    |{{#findings}}
    |<tr style="border-top: 1px solid #d0d7de;"><td>{{icon}}</td>{{#hasWhere}}<td>{{#link}}<a href="{{link}}" title="{{path}}">{{shortWhere}}</a>{{/link}}{{^link}}{{where}}{{/link}}</td>{{/hasWhere}}<td><code>{{rule}}</code></td><td>{{message}}</td><td>{{fix}}</td></tr>
    |{{/findings}}
    |{{#hasRows}}
    |</table>
    |{{/hasRows}}
    |{{#more}}
    |<p><em>+{{more}} {{labels.more}}</em></p>
    |{{/more}}
    |{{#details.any}}
    |<details><summary>{{labels.details}}</summary><ul>{{#details.lines}}<li>{{.}}</li>{{/details.lines}}</ul></details>
    |{{/details.any}}
    |<p style="color: #57606a; font-size: 0.9em;">{{footer}}</p>
    |</div>
    |</body>
    |</html>
    |
    """.trimMargin()

/** The bundled plain-text alternative of [HTML_TEMPLATE]. */
private val TEXT_TEMPLATE: String =
    """
    |{{headline}}{{#delta.text}} — {{delta.text}}{{/delta.text}}
    |{{#mr}}
    |{{ref}} {{title}}{{#url}} {{url}}{{/url}}
    |{{/mr}}
    |{{#findings}}
    |
    |{{icon}} {{rule}} · {{message}}
    |   {{where}}{{#fix}}
    |   {{labels.fix}}: {{fix}}{{/fix}}
    |{{/findings}}
    |{{#more}}
    |
    |+{{more}} {{labels.more}}
    |{{/more}}
    |
    |{{footer}}
    |
    """.trimMargin()

/** `[karandash] FAILED · <MR title>`, or the counts when there is no merge request; never a line break. */
internal fun emailSubject(view: ReportView): String {
    val verdict = view.verdict.uppercase()
    val about = view.mr?.title?.takeIf { it.isNotBlank() } ?: view.counts.text
    return listOf("[karandash] $verdict", about)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .replace('\r', ' ')
        .replace('\n', ' ')
}

/** The compact HTML body plus [ReportMediaKeys.SUBJECT] and [ReportMediaKeys.TEXT_BODY] metadata. */
internal fun compactHtml(
    view: ReportView,
    override: TemplateOverride,
    logger: Logger?,
): Pair<String, Map<String, String>> {
    val rows = mapOf("hasRows" to JsonPrimitive(view.findings.isNotEmpty()), "hasWhere" to JsonPrimitive(view.hasWhere()))
    val html = renderReportTemplate(HTML_TEMPLATE, view, TemplateEscapes::html, override, extra = rows, logger = logger)
    val text = renderReportTemplate(TEXT_TEMPLATE, view, TemplateEscapes::none)
    return html to mapOf(ReportMediaKeys.SUBJECT to emailSubject(view), ReportMediaKeys.TEXT_BODY to text)
}
