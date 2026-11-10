package io.karandash.helpers.reporter

import io.karandash.models.report.ReportView
import io.karandash.models.support.KarandashJson
import io.karandash.models.template.Mustache
import io.karandash.sdk.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A user's template for a format: `reporters.<format>.template` and `reporters.<format>.partials.<name>`, each usually
 * a `{ file: ... }` reference that config loading has already replaced with the file's content.
 */
@Serializable
data class TemplateOverride(
    val template: String? = null,
    val partials: Map<String, String> = emptyMap(),
)

/** [view] as template data, with medium-specific [extra] keys (such as pre-rendered sections) merged on top. */
fun ReportView.toTemplateData(extra: Map<String, JsonElement> = emptyMap()): JsonObject =
    JsonObject(KarandashJson.Default.encodeToJsonElement(ReportView.serializer(), this).jsonObject + extra)

/**
 * Renders [view] with the user's [override] template when set, else [bundled]. A user template that fails to parse or
 * render (or exceeds [maxOutputChars]) is logged at warn and the bundled one is used instead, so a broken template
 * never loses the report. Bundled partials are available to user templates under the same names unless overridden.
 */
fun renderReportTemplate(
    bundled: String,
    view: ReportView,
    escape: (String) -> String,
    override: TemplateOverride = TemplateOverride(),
    extra: Map<String, JsonElement> = emptyMap(),
    bundledPartials: Map<String, String> = emptyMap(),
    logger: Logger? = null,
    maxOutputChars: Int = Mustache.DEFAULT_MAX_OUTPUT_CHARS,
): String {
    val data = view.toTemplateData(extra)
    val partials = bundledPartials + override.partials
    val custom = override.template?.takeIf { it.isNotBlank() }
    if (custom != null) {
        try {
            return Mustache.render(custom, data, partials, escape, maxOutputChars)
        } catch (e: IllegalArgumentException) {
            logger?.warn("report template is invalid, using the built-in one: ${e.message}")
        } catch (e: IllegalStateException) {
            logger?.warn("report template failed, using the built-in one: ${e.message}")
        }
    }
    return Mustache.render(bundled, data, bundledPartials, escape, maxOutputChars)
}

/** [io.karandash.api.finding.FormattedReport.metadata] keys a format sets for the output that delivers it. */
object ReportMediaKeys {
    /** A message subject (`html`: `[karandash] FAILED · <MR title>`); an email output uses it unless configured. */
    const val SUBJECT = "subject"

    /** A plain-text rendering of the same report, sent as the alternative to an HTML body. */
    const val TEXT_BODY = "textBody"
}
