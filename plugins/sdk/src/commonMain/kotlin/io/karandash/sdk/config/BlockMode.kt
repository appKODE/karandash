package io.karandash.sdk.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one vocabulary of every `block*` rule key: `fail` reports an error, `warn` a warning, `off` nothing.
 *
 * Booleans stay accepted with each rule's historical meaning: `true` is [FAIL] everywhere, `false` is the
 * rule's [BlockModeSerializer.falseMeans] (`off` for most keys, `warn` for `cicdStatus.blockOnFailing`).
 */
public enum class BlockMode {
    FAIL,
    WARN,
    OFF,
    ;

    /** [finding] at this mode's severity (error for [FAIL], warning for [WARN]), or `null` for [OFF]. */
    public fun applyTo(finding: Finding): Finding? =
        when (this) {
            FAIL -> finding.copy(severity = Severity.ERROR)
            WARN -> finding.copy(severity = Severity.WARNING)
            OFF -> null
        }

    public companion object {
        /** Parses `fail | warn | off` (any case) or a boolean string; `false` maps to [falseMeans]. `null` when unknown. */
        public fun parse(
            value: String,
            falseMeans: BlockMode,
        ): BlockMode? =
            when (value.trim().lowercase()) {
                "true" -> FAIL
                "false" -> falseMeans
                else -> entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            }
    }
}

/**
 * Reads a [BlockMode] from `fail | warn | off` or a boolean alias, where `false` means [falseMeans];
 * writes the lowercase string form.
 */
public open class BlockModeSerializer(
    private val falseMeans: BlockMode,
) : KSerializer<BlockMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.karandash.sdk.config.BlockMode", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BlockMode,
    ): Unit = encoder.encodeString(value.name.lowercase())

    override fun deserialize(decoder: Decoder): BlockMode {
        val raw = if (decoder is JsonDecoder) {
            (decoder.decodeJsonElement() as? JsonPrimitive)?.content
        } else {
            decoder.decodeString()
        }
        return raw?.let { BlockMode.parse(it, falseMeans) }
            ?: throw SerializationException("expected one of fail, warn, off (or a boolean), got '$raw'")
    }
}

/** [BlockModeSerializer] where `false` means [BlockMode.OFF] (draft, conflict, file-changes keys). */
public object BlockModeFalseOffSerializer : BlockModeSerializer(BlockMode.OFF)

/** [BlockModeSerializer] where `false` means [BlockMode.WARN] (`cicdStatus.blockOnFailing`). */
public object BlockModeFalseWarnSerializer : BlockModeSerializer(BlockMode.WARN)
