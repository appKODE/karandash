package io.karandash.plugins.formats

import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.helpers.reporter.renderReportTemplate
import io.karandash.models.report.ReportView
import io.karandash.models.template.TemplateEscapes
import io.karandash.sdk.Logger

/** Findings a compact Slack message lists before the "N more" line. */
internal const val SLACK_COMPACT_FINDINGS = 5

/** Slack's limit on a header block's plain text. */
private const val MAX_SLACK_HEADER = 150

private const val MRKDWN = "mrkdwn"

/** What a broken user template falls back to: the compact layout as one mrkdwn section. */
private val SLACK_TEMPLATE =
    """
    |*{{headline}}*{{#delta.text}} · {{delta.text}}{{/delta.text}}
    |{{#findings}}
    |{{icon}} *{{rule}}* · {{message}}{{#where}} ({{where}}){{/where}}{{#fix}} · {{fix}}{{/fix}}
    |{{/findings}}
    |{{#more}}
    |_+{{more}} {{labels.more}}_
    |{{/more}}
    """.trimMargin()

/**
 * A user `template` (Slack-escaped `{{x}}`) rendered into one mrkdwn section, so the payload stays Block Kit; a
 * broken template falls back to [SLACK_TEMPLATE] with a warning on [logger].
 */
internal fun templatedSlackBlocks(
    view: ReportView,
    override: TemplateOverride,
    logger: Logger,
): List<SlackBlock> {
    val text = renderReportTemplate(SLACK_TEMPLATE, view, TemplateEscapes::slack, override, logger = logger)
    return listOf(SlackBlock(type = "section", text = SlackText(MRKDWN, capSlack(text, MAX_SLACK_SECTION_TEXT))))
}

/**
 * The standard and quiet Slack layout: the verdict header, a context line with the counts, the change since the last
 * run and the merge request link, at most [SLACK_COMPACT_FINDINGS] findings (icon, rule, message, where, fix) and the
 * "N more" line. [view] is built with that limit.
 */
internal fun compactSlackBlocks(view: ReportView): List<SlackBlock> {
    val icon = if (view.passed) "✅" else "❌"
    val header = SlackBlock(type = "header", text = SlackText("plain_text", "$icon karandash · ${view.verdict}".take(MAX_SLACK_HEADER)))
    val mrLink = view.mr?.let { mr ->
        val label = TemplateEscapes.slack(listOf(mr.ref, mr.title).filter { it.isNotBlank() }.joinToString(" "))
        mr.url?.let { "<$it|$label>" } ?: label.ifBlank { null }
    }
    val context = listOfNotNull(
        TemplateEscapes.slack(view.counts.text).ifBlank { null },
        view.delta
            ?.text
            ?.ifBlank { null }
            ?.let(TemplateEscapes::slack),
        mrLink,
    ).joinToString(" · ")
    val findings = view.findings.map { finding ->
        val text = buildString {
            append("${finding.icon} *${TemplateEscapes.slack(finding.rule)}* · ${TemplateEscapes.slack(finding.message)}")
            if (finding.where.isNotBlank()) {
                val where = TemplateEscapes.slack(finding.where)
                val short = TemplateEscapes.slack(finding.shortWhere.ifBlank { finding.where })
                append("\n").append(finding.link?.let { "<$it|$short>" } ?: "`$where`")
            }
            finding.fix?.takeIf { it.isNotBlank() }?.let {
                append(
                    "\n${TemplateEscapes.slack(view.labels.fix)}: ${TemplateEscapes.slack(it)}"
                )
            }
        }
        SlackBlock(type = "section", text = SlackText(MRKDWN, capSlack(text, MAX_SLACK_SECTION_TEXT)))
    }
    val more = view.more.takeIf { it > 0 }?.let {
        SlackBlock(type = "context", elements = listOf(SlackText(MRKDWN, "_+$it ${TemplateEscapes.slack(view.labels.more)}_")))
    }
    return listOfNotNull(header, context.ifBlank { null }?.let { SlackBlock(type = "context", elements = listOf(SlackText(MRKDWN, it))) }) +
        findings + listOfNotNull(more)
}
