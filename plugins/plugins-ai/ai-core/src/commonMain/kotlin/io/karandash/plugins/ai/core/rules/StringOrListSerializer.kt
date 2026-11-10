package io.karandash.plugins.ai.core.rules

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant [KSerializer] for `List<String>` used to parse AI-generated JSON.
 *
 * Some LLMs occasionally emit a bare string (or null) for a field whose schema
 * declares an array - e.g. `"reviewFocus": "only item"` instead of
 * `"reviewFocus": ["only item"]`. Without coercion this throws
 * [SerializationException] and the whole response is discarded as "unparseable",
 * losing the summary and other valid fields.
 *
 * Coercion rules on decode:
 * - [JsonArray] → decode as `List<String>`; non-primitive entries are converted
 *   via [JsonPrimitive.content] when possible, otherwise skipped.
 * - [JsonPrimitive] (string) → `listOf(content)` after trimming; blank → empty list.
 * - [JsonNull] → `emptyList()`.
 * - [JsonObject] → `emptyList()` (do not throw; preserves neighbouring fields).
 *
 * Encode always emits a [JsonArray].
 */
object StringOrListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("StringOrListSerializer requires JSON input")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> emptyList()
            is JsonArray -> element.mapNotNull { it.toPrimitiveStringOrNull() }
            is JsonPrimitive -> element.content.trim().let { if (it.isEmpty()) emptyList() else listOf(it) }
            is JsonObject -> emptyList()
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("StringOrListSerializer requires JSON output")
        delegate.serialize(jsonEncoder, value)
    }

    private fun kotlinx.serialization.json.JsonElement.toPrimitiveStringOrNull(): String? =
        when (this) {
            // JsonNull must be checked before JsonPrimitive - JsonNull extends JsonPrimitive.
            is JsonNull -> null

            is JsonPrimitive -> content

            else -> null
        }
}
