package io.karandash.models.report

import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.without
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.serialization.Serializable

/**
 * `report.style`: how much a human-facing report shows. Each style is a named set of `hideFields` plus the internal
 * metadata keys it leaves out of MR comments. Machine formats (JSON, SARIF, JUnit, rdjson) ignore it.
 *
 * [VERBOSE] keeps every field and the full layout; [STANDARD] is the default compact layout; [QUIET] also
 * drops explanations, attachments and the generic metadata list.
 */
@Serializable
enum class ReportStyle(
    val key: String,
    val hiddenFields: Set<FindingField>,
    val hiddenMetadataKeys: Set<String>,
) {
    QUIET(
        "quiet",
        setOf(
            FindingField.EXPLANATION,
            FindingField.ATTACHMENTS,
            FindingField.METADATA,
            FindingField.FINGERPRINT,
            FindingField.PRIORITY,
            FindingField.CONFIDENCE,
            FindingField.RELATED_FINDINGS,
            FindingField.CATEGORY,
            FindingField.ERROR_CODE,
        ),
        INTERNAL_METADATA_KEYS,
    ),
    STANDARD(
        "standard",
        setOf(
            FindingField.FINGERPRINT,
            FindingField.PRIORITY,
            FindingField.CONFIDENCE,
            FindingField.RELATED_FINDINGS,
            FindingField.ERROR_CODE,
        ),
        INTERNAL_METADATA_KEYS,
    ),
    VERBOSE("verbose", emptySet(), emptySet()),
    ;

    /** True for the full layout every format had before styles existed. */
    val legacyLayout: Boolean get() = this == VERBOSE

    /** [finding] without this style's hidden fields and metadata keys. */
    fun apply(finding: Finding): Finding =
        finding.without(hiddenFields).let { trimmed ->
            if (hiddenMetadataKeys.isEmpty()) trimmed else trimmed.copy(metadata = trimmed.metadata - hiddenMetadataKeys)
        }

    companion object {
        /** The style when none is configured. */
        val DEFAULT: ReportStyle = STANDARD

        /** Other accepted names: the console's `--style concise|full`. */
        private val ALIASES = mapOf("concise" to STANDARD, "full" to VERBOSE)

        /** The style named [name] (case-insensitive, aliases included); `null` when unknown. */
        fun parse(name: String?): ReportStyle? {
            val normalized = name?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == normalized } ?: ALIASES[normalized]
        }

        /** [parse], or [DEFAULT] when [name] is absent or unknown. */
        fun of(name: String?): ReportStyle = parse(name) ?: DEFAULT
    }
}

/** Metadata keys about how a finding was produced, not what it says: kept only by [ReportStyle.VERBOSE]. */
private val INTERNAL_METADATA_KEYS: Set<String> =
    setOf(WellKnownMetadataKeys.AI_MODEL, WellKnownMetadataKeys.AI_DURATION_MS, WellKnownMetadataKeys.RECLASSIFIED_FROM)
