package io.karandash.config

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-rule conditional gating with optional inline config overrides.
 * Stored in [RuleExecutionMeta.`when`]. Replaces the old `enabledWhen: String?` field.
 *
 * Simple form in YAML:   `when: 'has_ticket(PROJ)'`
 * Full form in YAML:
 * ```yaml
 * when:
 *   expression: 'has_ticket(PROJ) && has_tag(android)'
 *   override:
 *     required: true
 * ```
 *
 * The custom serializer handles both forms transparently during deserialization.
 */
@Serializable(with = WhenConfigSerializer::class)
data class WhenConfig(
    val expression: String,
    val override: JsonObject? = null,
) {
    companion object {
        /**
         * Parses a [JsonElement] into a [WhenConfig], supporting both simple string
         * and full object forms.
         *
         * @return parsed [WhenConfig], or null if the element is not a valid form.
         */
        fun fromJsonElement(element: JsonElement): WhenConfig? =
            when (element) {
                is JsonPrimitive -> if (element.isString) WhenConfig(expression = element.content) else null

                is JsonObject -> KarandashJson.Default.decodeFromJsonElement(
                    WhenConfigObjectSerializer,
                    element,
                )

                else -> null
            }
    }
}

/**
 * Internal serializer for the object form of [WhenConfig].
 * Used by [WhenConfigSerializer] to delegate object deserialization.
 */
@Serializable
private data class WhenConfigSurrogate(
    val expression: String,
    val override: JsonObject? = null,
)

/**
 * Serializes [WhenConfig] as either a string (when [WhenConfig.override] is null)
 * or an object with `expression` and `override` fields.
 *
 * Deserializes from both forms transparently:
 * - String: `"has_ticket(PROJ)"` -> `WhenConfig(expression = "has_ticket(PROJ)")`
 * - Object: `{ "expression": "...", "override": {...} }` -> full `WhenConfig`
 */
internal object WhenConfigSerializer : KSerializer<WhenConfig> {
    override val descriptor: SerialDescriptor = WhenConfigSurrogate.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: WhenConfig
    ) {
        if (value.override == null && encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonPrimitive(value.expression))
        } else {
            val surrogate = WhenConfigSurrogate(value.expression, value.override)
            encoder.encodeSerializableValue(WhenConfigSurrogate.serializer(), surrogate)
        }
    }

    override fun deserialize(decoder: Decoder): WhenConfig {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder
                .decodeSerializableValue(WhenConfigSurrogate.serializer())
                .let { WhenConfig(it.expression, it.override) }

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                WhenConfig(expression = element.content)
            }

            is JsonObject -> {
                val surrogate = KarandashJson.Default.decodeFromJsonElement(
                    WhenConfigSurrogate.serializer(),
                    element,
                )
                WhenConfig(surrogate.expression, surrogate.override)
            }

            else -> {
                error("WhenConfig must be a string or object, got: ${element::class.simpleName}")
            }
        }
    }
}

/**
 * Object-only serializer for [WhenConfig], used by [WhenConfig.fromJsonElement]
 * when the element is known to be a [JsonObject].
 */
internal val WhenConfigObjectSerializer: KSerializer<WhenConfig> =
    object : KSerializer<WhenConfig> {
        private val surrogateSerializer = WhenConfigSurrogate.serializer()
        override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

        override fun serialize(
            encoder: Encoder,
            value: WhenConfig
        ) {
            encoder.encodeSerializableValue(
                surrogateSerializer,
                WhenConfigSurrogate(value.expression, value.override),
            )
        }

        override fun deserialize(decoder: Decoder): WhenConfig {
            val surrogate = decoder.decodeSerializableValue(surrogateSerializer)
            return WhenConfig(surrogate.expression, surrogate.override)
        }
    }
