package io.karandash.models.finding

import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.Serializable

/**
 * The fields of a [Finding] as a report shows them: every format renders each one, and `hideFields` in a format's
 * config names the ones to leave out. [key] is the stable public name (also used by `report.style` presets).
 * [END_LINE] and [CODE_CHANGE] are parts of other fields ([Location.FileRef.endLine], [codeChange]) shown on their own.
 *
 * @property hideable `false` for the fields a finding means nothing without; asking to hide them only warns.
 */
@Serializable
enum class FindingField(
    val key: String,
    val hideable: Boolean = true,
) {
    SEVERITY("severity", hideable = false),
    MESSAGE("message", hideable = false),
    RULE("rule", hideable = false),
    LOCATION("location", hideable = false),
    END_LINE("endLine"),
    EXPLANATION("explanation"),
    SUGGESTION("suggestion"),
    METADATA("metadata"),
    CODE_CHANGE("codeChange"),
    FIX_SUGGESTIONS("fixSuggestions"),
    ATTACHMENTS("attachments"),
    FINGERPRINT("fingerprint"),
    PRIORITY("priority"),
    CONFIDENCE("confidence"),
    RELATED_FINDINGS("relatedFindings"),
    CATEGORY("category"),
    ERROR_CODE("errorCode"),
    ;

    companion object {
        /** Other accepted spellings of a field name, by normalized name. */
        private val ALIASES = mapOf("ruleid" to RULE)

        /**
         * Metadata keys a format reads to place or route a finding, or shows in a place of their own (fix hints, code
         * change, sections, AI summary kinds, score chips). Hiding [METADATA] keeps them; a generic metadata list
         * leaves them out.
         */
        val RENDERED_METADATA_KEYS: Set<String> =
            FIX_HINT_METADATA_KEYS +
                setOf(
                    PresentationKeys.SECTION,
                    PresentationKeys.SECTION_TITLE,
                    PresentationKeys.SECTION_ORDER,
                    WellKnownMetadataKeys.SUGGESTED_CODE,
                    WellKnownMetadataKeys.EXISTING_CODE,
                    WellKnownMetadataKeys.IS_MR_SUMMARY,
                    WellKnownMetadataKeys.IS_RISK_ADVISORY,
                    WellKnownMetadataKeys.IS_FIX_SUGGESTION,
                    WellKnownMetadataKeys.IS_CATEGORIZATION,
                    WellKnownMetadataKeys.IS_DESCRIPTION_QUALITY,
                    WellKnownMetadataKeys.IS_PR_SPLIT,
                    WellKnownMetadataKeys.IS_TICKET_COMPLIANCE,
                    WellKnownMetadataKeys.IS_GENERATED_DESCRIPTION,
                    WellKnownMetadataKeys.RISK_SCORE,
                    WellKnownMetadataKeys.RISK_FACTORS,
                    WellKnownMetadataKeys.WHY,
                    WellKnownMetadataKeys.WALKTHROUGH,
                    WellKnownMetadataKeys.ANCHOR_FINGERPRINT,
                    WellKnownMetadataKeys.LINE_DEMOTED,
                    WellKnownMetadataKeys.DIMENSION,
                    WellKnownMetadataKeys.BUG_INTRODUCED_BY,
                    WellKnownMetadataKeys.FIXED_BY,
                    WellKnownMetadataKeys.DRIFT_SCORE,
                    WellKnownMetadataKeys.OVERALL_SCORE,
                    WellKnownMetadataKeys.ERROR,
                    WellKnownMetadataKeys.CACHED,
                    WellKnownMetadataKeys.CATEGORY,
                    WellKnownMetadataKeys.CONFIDENCE,
                    WellKnownMetadataKeys.COMPLIANCE_SCORE,
                    WellKnownMetadataKeys.FULFILLED_REQUIREMENTS,
                    WellKnownMetadataKeys.MISSING_REQUIREMENTS,
                    WellKnownMetadataKeys.PARTIAL_REQUIREMENTS,
                    WellKnownMetadataKeys.SUGGESTED_TITLE,
                    WellKnownMetadataKeys.SUGGESTED_DESCRIPTION,
                    WellKnownMetadataKeys.SUGGESTED_LABELS,
                    WellKnownMetadataKeys.SUGGESTED_FIX,
                    WellKnownMetadataKeys.QUALITY_SCORE,
                    WellKnownMetadataKeys.ORIGINAL_RULE,
                )

        /**
         * The fields [names] hide, plus a warning per problem: one listing every unknown name with the valid ones, one
         * per field that cannot be hidden. Names match case-insensitively, kebab-case and snake_case included.
         */
        fun parse(names: List<String>): HiddenFields {
            val byName = entries.associateBy { normalize(it.key) } + ALIASES
            val resolved = names.associateWith { byName[normalize(it)] }
            val unknown = resolved.filterValues { it == null }.keys
            val fixed = resolved.values
                .filterNotNull()
                .filterNot { it.hideable }
                .distinct()
            val warnings = fixed.map { "hideFields: '${it.key}' cannot be hidden and is still shown" } +
                listOfNotNull(
                    unknown.takeIf { it.isNotEmpty() }?.let { names ->
                        "hideFields: unknown field(s) ${names.joinToString { "'$it'" }}; valid names: " +
                            entries.filter { it.hideable }.joinToString { it.key }
                    },
                )
            return HiddenFields(
                resolved.values
                    .filterNotNull()
                    .filter { it.hideable }
                    .toSet(),
                warnings
            )
        }

        private fun normalize(name: String): String = ModuleIdNormalizer.normalize(name)
    }
}

/** What `hideFields` asks for: the [fields] to leave out and the [warnings] to log about the names given. */
@Serializable
data class HiddenFields(
    val fields: Set<FindingField> = emptySet(),
    val warnings: List<String> = emptyList(),
)

/** This finding without [fields]: text fields cleared, lists emptied, [Finding.priority] back to the default. */
fun Finding.without(fields: Set<FindingField>): Finding {
    if (fields.isEmpty()) return this
    val ref = location as? Location.FileRef
    val codeKeys = setOf(WellKnownMetadataKeys.SUGGESTED_CODE, WellKnownMetadataKeys.EXISTING_CODE)
    val kept = metadata.filterKeys { key ->
        (FindingField.METADATA !in fields || key in FindingField.RENDERED_METADATA_KEYS) &&
            (FindingField.CODE_CHANGE !in fields || key !in codeKeys)
    }
    return copy(
        location = if (FindingField.END_LINE in fields && ref != null) ref.copy(endLine = null) else location,
        explanation = explanation.takeUnless { FindingField.EXPLANATION in fields },
        suggestion = suggestion.takeUnless { FindingField.SUGGESTION in fields },
        metadata = kept,
        fixSuggestions = if (FindingField.FIX_SUGGESTIONS in fields) emptyList() else fixSuggestions,
        attachments = if (FindingField.ATTACHMENTS in fields) emptyList() else attachments,
        fingerprint = fingerprint.takeUnless { FindingField.FINGERPRINT in fields },
        priority = if (FindingField.PRIORITY in fields) Finding.DEFAULT_PRIORITY else priority,
        confidence = confidence.takeUnless { FindingField.CONFIDENCE in fields },
        relatedFindings = if (FindingField.RELATED_FINDINGS in fields) emptyList() else relatedFindings,
        category = category.takeUnless { FindingField.CATEGORY in fields },
        errorCode = errorCode.takeUnless { FindingField.ERROR_CODE in fields },
    )
}
