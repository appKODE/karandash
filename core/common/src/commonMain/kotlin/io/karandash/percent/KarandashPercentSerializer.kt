package io.karandash.percent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes [KarandashPercent] as a human-readable string (e.g. `"70%"`, `"0%"`). */
object KarandashPercentSerializer : KSerializer<KarandashPercent> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KarandashPercent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): KarandashPercent {
        val value = decoder.decodeString()
        return KarandashPercent.parse(value)
    }

    override fun serialize(
        encoder: Encoder,
        value: KarandashPercent,
    ) {
        encoder.encodeString(value.toString())
    }
}
