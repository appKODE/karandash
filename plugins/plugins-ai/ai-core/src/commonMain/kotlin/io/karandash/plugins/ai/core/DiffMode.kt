package io.karandash.plugins.ai.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Controls what diff content is sent to the LLM for analysis.
 *
 * - [CHANGED] - only changed lines (default, uses `diffContent`)
 * - [PATCH] - raw unified diff without file path headers
 * - [FULL] - entire new file content (falls back to [CHANGED] when `newContent` is unavailable)
 * - [ADDED_ONLY] - only added (`+`) lines; context and deleted lines replaced with empty lines
 */
@Serializable(with = DiffModeSerializer::class)
enum class DiffMode {
    /** Only changed lines - the default behavior using [io.karandash.models.FileChange.diffContent]. */
    CHANGED,

    /** Raw unified diff without `###` file path headers, for tool-parseable output. */
    PATCH,

    /** Entire new file content. Falls back to [CHANGED] when `newContent` is not available. */
    FULL,

    /** Only added (`+`) lines; context and deleted lines replaced with empty lines for maximum token reduction. */
    ADDED_ONLY;

    companion object {
        /** Parse a [DiffMode] from a string, defaulting to [CHANGED] for unknown values. */
        fun fromString(value: String): DiffMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CHANGED
    }
}

/**
 * Serializes [DiffMode] as a lowercase string, deserializes case-insensitively
 * with [DiffMode.CHANGED] as fallback for unknown values.
 */
internal object DiffModeSerializer : KSerializer<DiffMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DiffMode", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: DiffMode
    ) = encoder.encodeString(value.name.lowercase())

    override fun deserialize(decoder: Decoder): DiffMode = DiffMode.fromString(decoder.decodeString())
}
