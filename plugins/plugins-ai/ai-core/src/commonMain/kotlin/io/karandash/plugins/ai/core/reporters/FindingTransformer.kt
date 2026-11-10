package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.template.Mustache
import io.karandash.models.template.TemplateEscapes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Finding output transforms: reshapes findings into custom formats using templates.
 * Templates are [Mustache] (no escaping), for mapping to external schemas (JIRA, PagerDuty, etc.): an unknown
 * placeholder renders empty, as everywhere else Mustache is used.
 */
object FindingTransformer {
    /**
     * Substitutes `{{field}}` placeholders in [template] with values from [finding].
     *
     * Supported placeholders:
     * - `{{rule}}`, `{{severity}}`, `{{message}}`, `{{explanation}}`, `{{suggestion}}`
     * - `{{file}}`, `{{line}}` (from [Location.FileRef]; empty string when absent)
     * - `{{commit}}` (from [Location.CommitRef]; empty string when absent)
     * - `{{meta.KEY}}` for any key in [Finding.metadata]
     *
     * @param finding The finding to render.
     * @param template Template string with `{{field}}` placeholders.
     * @return Rendered string; an unknown placeholder renders empty.
     * @throws IllegalArgumentException when [template] is malformed Mustache (an unclosed section).
     */
    fun transform(
        finding: Finding,
        template: String
    ): String = Mustache.render(template, data(finding), escape = TemplateEscapes::none)

    private fun data(finding: Finding): JsonObject {
        val loc = finding.location
        return buildJsonObject {
            put("rule", finding.rule)
            put("severity", finding.severity.name)
            put("message", finding.message)
            put("explanation", finding.explanation.orEmpty())
            put("suggestion", finding.suggestion.orEmpty())
            put("file", (loc as? Location.FileRef)?.path.orEmpty())
            put("line", (loc as? Location.FileRef)?.line?.toString().orEmpty())
            put("commit", (loc as? Location.CommitRef)?.sha.orEmpty())
            put("meta", JsonObject(finding.metadata.mapValues { JsonPrimitive(it.value) }))
        }
    }

    /**
     * Transforms all [findings] with [template] and joins them with [separator].
     *
     * @param findings Findings to render.
     * @param template Template string with `{{field}}` placeholders.
     * @param separator String placed between rendered entries. Defaults to newline.
     * @return All rendered findings joined by [separator], or the parse error when [template] is malformed, so the
     *   report shows what to fix instead of failing the run.
     */
    fun transformAll(
        findings: List<Finding>,
        template: String,
        separator: String = "\n",
    ): String =
        try {
            findings.joinToString(separator) { transform(it, template) }
        } catch (e: IllegalArgumentException) {
            "Invalid finding template: ${e.message}"
        }
}
