package io.karandash.duration

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes [KarandashDuration] as a human-readable string (e.g. `"30s"`, `"1h 30m"`). */
object KarandashDurationSerializer : KSerializer<KarandashDuration> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KarandashDuration", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): KarandashDuration {
        val value = decoder.decodeString()
        return KarandashDuration.parse(value)
    }

    override fun serialize(
        encoder: Encoder,
        value: KarandashDuration
    ) {
        encoder.encodeString(value.toString())
    }
}
