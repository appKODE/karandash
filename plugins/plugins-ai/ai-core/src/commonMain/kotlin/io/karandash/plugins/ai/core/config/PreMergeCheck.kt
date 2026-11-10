package io.karandash.plugins.ai.core.config

import io.karandash.sdk.Logger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One `ai.checks` entry: a pre-merge check the model answers pass, fail or n/a for, with a one-line reason.
 *
 * @property name Row label in the checklist.
 * @property instruction What must hold for the check to pass.
 * @property paths Globs; when set and no changed file matches, the check is n/a without asking the model.
 * @property mode [PreMergeCheckMode.ERROR] makes a failing check an ERROR finding (non-zero exit), the default
 *   [PreMergeCheckMode.WARN] a WARNING.
 */
@Serializable
data class PreMergeCheck(
    val name: String,
    val instruction: String,
    val paths: List<String> = emptyList(),
    val mode: PreMergeCheckMode = PreMergeCheckMode.WARN,
) {
    /** True when a failure of this check should fail the run. */
    val blocking: Boolean get() = mode == PreMergeCheckMode.ERROR

    companion object {
        /** Warns once per `ai.checks` entry whose `mode` is neither `warn` nor `error`; those decode as `warn`. */
        fun warnUnknownModes(
            rawChecks: JsonElement?,
            logger: Logger?,
        ) {
            if (logger == null) return
            (rawChecks as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { check ->
                val raw = (check["mode"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                if (PreMergeCheckMode.parse(raw) == null) {
                    val name = (check["name"] as? JsonPrimitive)?.contentOrNull
                    logger.warn("ai.checks '$name': unknown mode '$raw', using warn (expected warn or error)")
                }
            }
        }
    }
}

/** `ai.checks[].mode`; an unknown value decodes as [WARN] (see [PreMergeCheck.warnUnknownModes]). */
@Serializable(with = PreMergeCheckModeSerializer::class)
enum class PreMergeCheckMode(
    val value: String,
) {
    WARN("warn"),
    ERROR("error"),
    ;

    companion object {
        /** Case-insensitive match on [value]; null for anything else. */
        fun parse(raw: String): PreMergeCheckMode? = entries.firstOrNull { it.value.equals(raw.trim(), ignoreCase = true) }
    }
}

internal object PreMergeCheckModeSerializer : KSerializer<PreMergeCheckMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PreMergeCheckMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PreMergeCheckMode =
        PreMergeCheckMode.parse(decoder.decodeString()) ?: PreMergeCheckMode.WARN

    override fun serialize(
        encoder: Encoder,
        value: PreMergeCheckMode,
    ) = encoder.encodeString(value.value)
}
