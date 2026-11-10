package io.karandash.cli.commands.validate

import io.karandash.api.finding.ValidationReport
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.HiddenFields
import io.karandash.models.finding.inLanguage
import io.karandash.models.finding.without
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The format config key every format takes: the [FindingField] names to leave out of the report. */
internal const val HIDE_FIELDS_KEY = "hideFields"

/**
 * The `hideFields` of [formatConfig] (a list of names, or one name) parsed by [FindingField.parse], and the config
 * without the key, so a format never sees it. A config without the key comes back as it is.
 */
internal fun splitHideFields(formatConfig: JsonElement): Pair<HiddenFields, JsonElement> {
    val value = (formatConfig as? JsonObject)?.get(HIDE_FIELDS_KEY) ?: return HiddenFields() to formatConfig
    val names = when (value) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonObject -> listOf(value.toString())
    }
    return FindingField.parse(names) to JsonObject(formatConfig - HIDE_FIELDS_KEY)
}

/** This report with [fields] taken out of every finding it holds. */
internal fun ValidationReport.without(fields: Set<FindingField>): ValidationReport =
    if (fields.isEmpty()) this else mapFindings { it.without(fields) }

/** This report with every finding in [language] ([inLanguage]), the form a format with that `language` gets. */
internal fun ValidationReport.inLanguage(language: String?): ValidationReport = mapFindings { it.inLanguage(language) }

private fun ValidationReport.mapFindings(transform: (Finding) -> Finding): ValidationReport {
    fun List<Finding>.mapped() = map(transform)
    return copy(
        findings = findings.mapped(),
        summary = summary.copy(
            errorFindings = summary.errorFindings.mapped(),
            warningFindings = summary.warningFindings.mapped(),
            infoFindings = summary.infoFindings.mapped(),
        ),
        groupedByLocation = groupedByLocation.mapValues { (_, list) -> list.mapped() },
        inlineEligible = inlineEligible.mapped(),
        summaryOnly = summaryOnly.mapped(),
    )
}
