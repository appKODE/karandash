package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.helpers.reporter.ReportViewOptions
import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.helpers.reporter.renderReportTemplate
import io.karandash.helpers.reporter.toView
import io.karandash.models.report.MrView
import io.karandash.models.report.ReportStyle
import io.karandash.models.template.TemplateEscapes
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.PluginContext
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.serialization.Serializable

/**
 * Config for [TemplateFormatModule]: the user's Mustache [template] (usually `{ file: ... }`) and [partials] over the
 * report view, how `{{name}}` is escaped ([escape]: `none` (default), `html`, `markdown`/`md`, `slack` or `json`),
 * and the view inputs the other formats take: [language], [style], [mr] and [maxFindings].
 */
@Serializable
data class TemplateFormatConfig(
    val template: String? = null,
    val partials: Map<String, String> = emptyMap(),
    val escape: String? = null,
    val language: String? = null,
    val style: String? = null,
    val mr: MrView? = null,
    val maxFindings: Int? = null,
) : ReporterConfig

/**
 * A custom format: renders the report view through the user's logic-less Mustache template, for a medium the
 * built-in formats do not cover (a Teams card, a chat message, a plain-text digest). The template sees the same data
 * as `karandash format schema` prints; it cannot read files or the environment, and its output is capped. Without a
 * template, or when the template is broken (logged at warn), it renders the verdict and findings as plain text.
 */
class TemplateFormatModule : AbstractTypedFormatModule<TemplateFormatConfig>(TemplateFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Template Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results with a user Mustache template."

    private var logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: TemplateFormatConfig,
    ): FormattedReport {
        val options = ReportViewOptions(
            language = config.language,
            mr = config.mr,
            maxFindings = config.maxFindings ?: Int.MAX_VALUE,
            style = ReportStyle.of(config.style),
        )
        val body = renderReportTemplate(
            bundled = PLAIN_TEMPLATE,
            view = report.toView(options),
            escape = escaper(config.escape),
            override = TemplateOverride(config.template, config.partials),
            logger = logger,
        )
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }

    private fun escaper(name: String?): (String) -> String {
        if (name.isNullOrEmpty()) return TemplateEscapes::none
        return TemplateEscapes.byName(name) ?: TemplateEscapes::none.also {
            logger.warn("template format: unknown escape '$name', using none (${TemplateEscapes.BY_NAME.keys.joinToString(", ")})")
        }
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.template"

        /** Format identifier used in [FormattedReport.formatId] and `reporters.<name>.format`. */
        const val FORMAT_ID = "template"

        /** The fallback when no template is set or the user's template fails. */
        internal val PLAIN_TEMPLATE: String =
            """
            |{{headline}}{{#delta.text}} — {{delta.text}}{{/delta.text}}
            |{{#findings}}
            |{{icon}} {{rule}} · {{message}} ({{where}}){{#fix}} — {{fix}}{{/fix}}
            |{{#why}}
            |  {{why}}
            |{{/why}}
            |{{/findings}}
            |{{#more}}
            |+{{more}} {{labels.more}}
            |{{/more}}
            |{{footer}}
            |
            """.trimMargin()
    }
}
