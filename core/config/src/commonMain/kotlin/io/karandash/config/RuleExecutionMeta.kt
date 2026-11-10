package io.karandash.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Execution metadata for a configured rule.
 *
 * Controls when the rule runs relative to other rules ([priority]), whether
 * an ERROR finding from this rule should stop lower-priority groups ([bailOnFail]),
 * a condition that gates execution ([when]), and which execution pass the rule
 * belongs to ([mode]).
 *
 * @property priority Group number (1–999). Rules with the same priority run in parallel;
 *   groups run sequentially in ascending order. Default: 100.
 * @property bailOnFail When `true` and this rule produces an [io.karandash.models.finding.Severity.ERROR]
 *   finding, all lower-priority groups are skipped. Default: `false`.
 * @property when Optional condition gating rule execution. When non-null, the rule is only
 *   executed if [WhenConfig.expression] evaluates to `true`. If the condition is true and
 *   [WhenConfig.override] is non-null, the override is deep-merged into the rule's config.
 * @property mode Execution pass assignment. [RuleMode.NORMAL] rules run in pass 1;
 *   [RuleMode.SUPPLEMENT] and [RuleMode.FALLBACK] rules run in pass 2 with access
 *   to pass-1 findings via [io.karandash.api.context.ValidationContext].
 * @property suggestion User-configured suggestion text that overrides validator defaults.
 *   When non-null, this value replaces any suggestion set by the rule module itself.
 * @property explanation User-configured explanation text that overrides validator defaults.
 *   When non-null, this value replaces any explanation set by the rule module itself.
 * @property concernArea Identifies the concern area for [RuleMode.FALLBACK] rules.
 *   When set, a FALLBACK rule is skipped if any pass-1 rule with the same concern area
 *   produced findings. When null, the FALLBACK rule is treated as [RuleMode.SUPPLEMENT]
 *   (always runs in pass 2).
 * @property severity User-configured severity (`error`, `warning`, `info`) forced onto every finding
 *   this rule produces. Only a scalar `severity:` is meta.
 * @property excludePaths Glob patterns; a finding of this rule located in a file matching one of them is dropped
 *   after the rule ran (every rule, AI included). Findings without a file location are kept.
 * @property severityCap Shadow mode for this rule (`error`, `warning`, `info`): its findings are lowered to at most
 *   this severity by the `severity-cap` pipeline extension, which reads it from the raw config and lets it win over the
 *   top-level `severity-cap:`. Kept here so the key is stripped from the rule's own config. Not encoded when unset,
 *   so effective-config snapshots stay unchanged.
 */
@Serializable
data class RuleExecutionMeta(
    val priority: Int = DEFAULT_PRIORITY,
    val bailOnFail: Boolean = false,
    @SerialName("when") val `when`: WhenConfig? = null,
    val mode: RuleMode = RuleMode.NORMAL,
    val suggestion: String? = null,
    val explanation: String? = null,
    val concernArea: String? = null,
    val severity: String? = null,
    val excludePaths: List<String> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val severityCap: String? = null,
) {
    companion object {
        const val DEFAULT_PRIORITY = 100
        const val SEVERITY_KEY = "severity"
        const val MIN_PRIORITY = 1
        const val MAX_PRIORITY = 999

        /**
         * All serialized field names for [RuleExecutionMeta], derived from the serializer descriptor.
         * Used to filter meta fields from rule config maps without hardcoded string sets.
         */
        val META_FIELD_NAMES: Set<String> by lazy {
            val d = serializer().descriptor
            (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        }
    }

    init {
        require(priority in MIN_PRIORITY..MAX_PRIORITY) {
            "priority must be in $MIN_PRIORITY..$MAX_PRIORITY, got $priority"
        }
    }
}

/**
 * Execution mode controlling which pass a rule runs in.
 *
 * The validation engine runs rules in two passes:
 * - **Pass 1:** All [NORMAL] rules, grouped by priority, executed in parallel within groups.
 * - **Pass 2:** All [SUPPLEMENT] and [FALLBACK] rules, with access to pass-1 findings
 *   via [io.karandash.api.context.ValidationContext].
 */
@Serializable(with = RuleModeSerializer::class)
enum class RuleMode {
    /** Runs in pass 1. Default for all existing rules. */
    NORMAL,

    /** Runs in pass 2, receives pass-1 findings as context. Always runs. */
    SUPPLEMENT,

    /**
     * Runs in pass 2 only if pass-1 produced no findings for this rule's concern area.
     * Concern area is determined by the module's strategy config (e.g., AI `covers` declarations).
     */
    FALLBACK;

    companion object {
        /**
         * Parse a rule mode from a string value (case-insensitive).
         *
         * @return The matching [RuleMode], or [NORMAL] if [value] is null or does not match.
         */
        fun fromString(value: String?): RuleMode =
            if (value == null) NORMAL else entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * Serializes [RuleMode] as a lowercase string, deserializes case-insensitively
 * with [RuleMode.NORMAL] as fallback for unknown values.
 */
internal object RuleModeSerializer : KSerializer<RuleMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RuleMode", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RuleMode,
    ) = encoder.encodeString(value.name.lowercase())

    override fun deserialize(decoder: Decoder): RuleMode = RuleMode.fromString(decoder.decodeString())
}
