package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Severity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Configuration for the post-filter AI finding enricher pipeline.
 *
 * When enabled, the enricher sends non-AI validation findings to the LLM
 * to produce improved messages, explanations, and actionable suggestions.
 *
 * @param enabled Whether the enricher is active. Defaults to false (opt-in).
 * @param enrichNonAiFindings When true, AI-generated findings (rule prefix `ai-`) are skipped since they are already rich.
 * @param skipSeverities Findings with these severities are not sent to the LLM.
 * @param maxFindings Maximum number of findings to enrich per validation run.
 */
@Serializable
data class EnricherConfig(
    val enabled: Boolean = false,
    val enrichNonAiFindings: Boolean = true,
    @Serializable(with = SeveritySetSerializer::class)
    val skipSeverities: Set<Severity> = setOf(Severity.INFO),
    val maxFindings: Int = DEFAULT_MAX_FINDINGS,
) {
    companion object {
        const val DEFAULT_MAX_FINDINGS = 20
    }
}

/**
 * Serializes a `Set<Severity>` as a set of lowercase strings and deserializes
 * case-insensitively, ignoring unrecognised severity names.
 */
internal object SeveritySetSerializer : KSerializer<Set<Severity>> {
    private val delegate = SetSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Set<Severity>
    ) {
        delegate.serialize(encoder, value.map { it.name.lowercase() }.toSet())
    }

    override fun deserialize(decoder: Decoder): Set<Severity> {
        val strings = delegate.deserialize(decoder)
        return strings
            .mapNotNull { s ->
                Severity.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
            }.toSet()
    }
}
