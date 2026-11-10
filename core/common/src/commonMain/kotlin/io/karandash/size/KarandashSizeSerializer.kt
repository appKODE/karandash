package io.karandash.size

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes [KarandashSize] as a human-readable string (e.g. `"10mb"`, `"1gb"`). */
object KarandashSizeSerializer : KSerializer<KarandashSize> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KarandashSize", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): KarandashSize {
        val value = decoder.decodeString()
        return KarandashSize.parse(value)
    }

    override fun serialize(
        encoder: Encoder,
        value: KarandashSize,
    ) {
        encoder.encodeString(value.toString())
    }
}
