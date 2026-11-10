package io.karandash.plugins.ai.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Execution strategy for an AI sub-rule relative to regular rules.
 *
 * Controls whether and how an AI sub-rule runs based on findings
 * from rules listed in [AiRuleStrategyConfig.covers].
 */
@Serializable(with = AiRuleStrategySerializer::class)
enum class AiRuleStrategy {
    /** Always run regardless of other rules' findings. Default behavior. */
    ALWAYS,

    /** Skip if any [AiRuleStrategyConfig.covers] rule produced findings at or above [AiRuleStrategyConfig.minSeverity]. */
    FALLBACK,

    /** Always run, but include prior findings from [AiRuleStrategyConfig.covers] rules in the AI prompt for richer analysis. */
    SUPPLEMENT;

    companion object {
        /**
         * Parses a strategy string (case-insensitive, supports kebab-case).
         * Returns [ALWAYS] for unrecognized values.
         */
        fun fromString(value: String): AiRuleStrategy =
            entries.firstOrNull {
                it.name.equals(value.replace("-", "_"), ignoreCase = true)
            } ?: ALWAYS
    }
}

/**
 * Per-AI-subrule strategy configuration.
 *
 * Parsed from the `rules:` block inside the `ai-analysis` config entry:
 * ```yaml
 * rules:
 *   ai-analysis:
 *     rules:
 *       ai-description-quality:
 *         strategy: fallback
 *         covers: [title-format]
 *         min-severity: warning
 * ```
 *
 * @property strategy How this AI sub-rule relates to regular rules.
 * @property covers Rule IDs that this AI sub-rule overlaps with. Used with
 *   [AiRuleStrategy.FALLBACK] to check if covered rules already found issues,
 *   and with [AiRuleStrategy.SUPPLEMENT] to include relevant prior findings in the AI prompt.
 * @property minSeverity Only consider [covers] findings at or above this severity level.
 *   Expressed as a severity name string ("info", "warning", "error"). Default: "info".
 */
@Serializable
data class AiRuleStrategyConfig(
    val strategy: AiRuleStrategy = AiRuleStrategy.ALWAYS,
    val covers: Set<String> = emptySet(),
    val minSeverity: String = "info",
) {
    companion object
}

/**
 * Serializes [AiRuleStrategy] as a lowercase string, deserializes case-insensitively
 * with kebab-case support. Falls back to [AiRuleStrategy.ALWAYS] for unknown values.
 */
internal object AiRuleStrategySerializer : KSerializer<AiRuleStrategy> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AiRuleStrategy", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AiRuleStrategy
    ) = encoder.encodeString(value.name.lowercase())

    override fun deserialize(decoder: Decoder): AiRuleStrategy = AiRuleStrategy.fromString(decoder.decodeString())
}
