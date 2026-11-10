package io.karandash.sdk.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Placeholder config for modules that require no configuration.
 *
 * Use [serializer] as the [KSerializer] parameter when a [TypedReporterModule] or
 * [TypedRuleModule] does not accept any user-facing configuration properties.
 */
public object EmptyConfig {
    /** [KSerializer] that always produces [EmptyConfig] and declares no properties. */
    public fun serializer(): KSerializer<EmptyConfig> = EmptyConfigSerializer
}

internal object EmptyConfigSerializer : KSerializer<EmptyConfig> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("EmptyConfig")

    override fun serialize(
        encoder: Encoder,
        value: EmptyConfig
    ) {
        val composite = encoder.beginStructure(descriptor)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): EmptyConfig {
        val composite = decoder.beginStructure(descriptor)
        composite.endStructure(descriptor)
        return EmptyConfig
    }
}
