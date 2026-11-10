package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.helpers.reporter.ReportViewOptions
import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.helpers.reporter.displayName
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
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.PluginContext
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.serialization.Serializable
import kotlin.time.Clock

private const val MAX_FINDINGS_IN_MESSAGE = 10
private const val MAX_SLACK_BLOCK_CONTENT = 2900

/** Slack rejects the whole message (`invalid_blocks`) when a section's text exceeds this. */
internal const val MAX_SLACK_SECTION_TEXT = 3000
private const val SLACK_MRKDWN = "mrkdwn"
private const val FENCE = "```"

/** Marks text the cap or the attachment limit cut. */
private const val TRUNCATION_MARKER = "\u2026"

/** Room kept for the marker, newline and closing fence appended when the cap cuts inside a code block. */
private const val TRUNCATION_RESERVE = 5

/** A backtick pair with a zero-width space between, so attachment text can never open or close a fence. */
private const val BROKEN_FENCE = "`\u200B``"

/**
 * Config for [SlackFormatModule]: the fix-hint [language] the pipeline takes from `report.language`, the
 * `report.style` preset ([style]; `verbose` keeps the full per-finding layout) and the merge request [mr] the
 * pipeline adds for the link. [template] (usually `{ file: ... }`) and [partials] replace the `standard` and `quiet`
 * layout with one mrkdwn section rendered from the template; `{{x}}` is Slack-escaped.
 */
@Serializable
data class SlackFormatConfig(
    val language: String? = null,
    val style: String? = null,
    val mr: MrView? = null,
    val template: String? = null,
    val partials: Map<String, String> = emptyMap(),
) : ReporterConfig

/**
 * Pure formatter that converts a [ValidationReport] into Slack Block Kit JSON.
 *
 * Extracts the Block Kit payload building from `SlackReporterModule`. No I/O or
 * webhook delivery is performed -- the resulting [FormattedReport.body] is
 * delivered by an output module.
 */
class SlackFormatModule : AbstractTypedFormatModule<SlackFormatConfig>(SlackFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Slack Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Formats validation results as Slack Block Kit JSON."

    private var logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.slack"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "slack"
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: SlackFormatConfig,
    ): FormattedReport {
        val style = ReportStyle.of(config.style)
        val payload = if (style.legacyLayout) {
            buildSlackPayload(report.findings, report.passed, config.language)
        } else {
            val options = ReportViewOptions(language = config.language, mr = config.mr, maxFindings = SLACK_COMPACT_FINDINGS, style = style)
            val view = report.toView(options)
            val blocks = if (config.template.isNullOrBlank()) {
                compactSlackBlocks(view)
            } else {
                templatedSlackBlocks(view, TemplateOverride(config.template, config.partials), logger)
            }
            SlackPayload(blocks = blocks)
        }
        val body = KarandashJson.Plain.encodeToString(payload)
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }

    private fun buildSlackPayload(
        findings: List<Finding>,
        passed: Boolean,
        language: String?,
    ): SlackPayload {
        val statusEmoji = if (passed) ":white_check_mark:" else ":x:"
        val statusText = verdictLabel(passed, language)

        val blocks = mutableListOf<SlackBlock>()

        blocks.add(
            SlackBlock(
                type = "header",
                text = SlackText("plain_text", SlackLayoutText.TITLE.of(language, "$statusEmoji $statusText")),
            ),
        )

        val summary = findings.summarize()
        val summaryText = if (findings.isEmpty()) {
            "${SlackLayoutText.ALL_PASSED.of(language)} :tada:"
        } else {
            SlackLayoutText.COUNTS.of(language, summary.errors, summary.warnings, summary.info)
        }

        blocks.add(
            SlackBlock(
                type = "section",
                text = SlackText(SLACK_MRKDWN, summaryText),
            ),
        )

        if (findings.isNotEmpty()) {
            appendFindingBlocks(blocks, findings, language)
        }

        blocks.add(SlackBlock(type = "divider"))
        blocks.add(
            SlackBlock(
                type = "context",
                elements = listOf(
                    SlackText(SLACK_MRKDWN, "_${SlackLayoutText.GENERATED.of(language, Clock.System.now().epochSeconds)}_"),
                ),
            ),
        )

        return SlackPayload(blocks = blocks)
    }

    private fun appendFindingBlocks(
        blocks: MutableList<SlackBlock>,
        findings: List<Finding>,
        language: String?,
    ) {
        blocks.add(SlackBlock(type = "divider"))

        findings.take(MAX_FINDINGS_IN_MESSAGE).forEach { finding ->
            val emoji = when (finding.severity) {
                Severity.ERROR -> ":x:"
                Severity.WARNING -> ":warning:"
                Severity.INFO -> ":information_source:"
            }
            val findingText = buildFindingText(emoji, finding, language)
            blocks.add(
                SlackBlock(
                    type = "section",
                    text = SlackText(SLACK_MRKDWN, findingText),
                ),
            )
        }

        if (findings.size > MAX_FINDINGS_IN_MESSAGE) {
            blocks.add(
                SlackBlock(
                    type = "context",
                    elements = listOf(
                        SlackText(
                            SLACK_MRKDWN,
                            "_${SlackLayoutText.MORE.count(language, findings.size - MAX_FINDINGS_IN_MESSAGE)}_",
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * Builds one finding's mrkdwn section. `&`, `<` and `>` are escaped as Slack requires, so text taken from the
 * MR (`<!channel>`, `<url|label>`) is shown literally instead of pinging or linking. The escaped result is
 * capped at [MAX_SLACK_SECTION_TEXT] without cutting an entity in half, and a code fence the cap left open is closed.
 */
internal fun buildFindingText(
    emoji: String,
    finding: Finding,
    language: String? = null,
): String {
    val text = buildString {
        append("$emoji *${finding.rule}*\n${finding.message}")
        finding.location?.let { append("\n`${it.toRangeDisplayString()}`") }
        finding.fixHintLines(language).forEach { append("\n$it") }
        finding.explanation?.let { append("\n$it") }
        val metadata = (finding.metadata - FIX_HINT_METADATA_KEYS).toList() + finding.facts() +
            listOfNotNull(finding.errorCode?.let { "errorCode" to it })
        if (metadata.isNotEmpty()) {
            append("\n" + metadata.joinToString(" | ") { (k, v) -> "_$k: ${v}_" })
        }
        for (attachment in finding.attachments) {
            append("\n*${attachment.displayName}*\n$FENCE${fenceSafe(attachment.content)}$FENCE")
        }
    }
    return capEscaped(text, MAX_SLACK_SECTION_TEXT)
}

/** At most [MAX_SLACK_BLOCK_CONTENT] chars, cut on a whole code point, marked when cut, with no fence of its own. */
private fun fenceSafe(content: String): String {
    val body = content.take(MAX_SLACK_BLOCK_CONTENT).dropLastWhile { it.isHighSurrogate() }
    return body.replace(FENCE, BROKEN_FENCE) + if (body.length < content.length) TRUNCATION_MARKER else ""
}

/**
 * Escapes [text] for Slack and cuts it to [limit] via [capSlack], closing a fence the cut left open (a fence cut in
 * half is dropped first).
 */
private fun capEscaped(
    text: String,
    limit: Int,
): String {
    val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if (escaped.length <= limit) return escaped
    val kept = cutSlack(escaped, limit - TRUNCATION_RESERVE).dropLastWhile { it == '`' }
    val open = kept.split(FENCE).size % 2 == 0
    return kept + TRUNCATION_MARKER + if (open) "\n$FENCE" else ""
}

/** Already Slack-escaped [text] cut to [limit] on a whole escape entity and code point, marked when cut. */
internal fun capSlack(
    text: String,
    limit: Int,
): String = if (text.length <= limit) text else cutSlack(text, limit - TRUNCATION_RESERVE) + TRUNCATION_MARKER

/** The first [max] chars of escaped [text], minus a trailing high surrogate or partial `&amp;`/`&lt;`/`&gt;`. */
private fun cutSlack(
    text: String,
    max: Int,
): String {
    val kept = text.take(max).dropLastWhile { it.isHighSurrogate() }
    val amp = kept.lastIndexOf('&')
    return if (amp >= 0 && ';' !in kept.substring(amp)) kept.substring(0, amp) else kept
}

@Serializable
internal data class SlackPayload(
    val channel: String? = null,
    val blocks: List<SlackBlock>,
)

@Serializable
internal data class SlackBlock(
    val type: String,
    val text: SlackText? = null,
    val elements: List<SlackText>? = null,
)

@Serializable
internal data class SlackText(
    val type: String,
    val text: String,
)

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # slack

    **Slack Format**

    Renders the report as Slack Block Kit JSON. Delivery (webhook, bot token, channel) is configured on the
    `slack` output, not here.

    ## Properties
    - `language: String` - report language for the verdict and fix hints (default: `report.language`)
    - `style: String` - `standard`, `quiet` or `verbose` (the full per-finding layout); default `report.style`
    - `template: String` - Mustache template (usually `{ file: ... }`) replacing the standard and quiet layout; `{{x}}` is Slack-escaped
    - `partials: Map<String, String>` - partials the template can include as `{{>name}}`
    - `mr` - set by the pipeline: the merge request the message links to

    ## Example
    ```
    reporters:
      slack:
        style: quiet
        template: { file: slack.mustache }
    ```
""".trimIndent()
