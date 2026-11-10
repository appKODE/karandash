package io.karandash.loader.bridge

import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.support.suspendCatching
import io.karandash.loader.BridgeDeserializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The CLI_COMMAND and CONFIG_FORMAT ops of any guest, read through its [BridgeTransport]: one implementation for
 * every transport, so the parts [asRegisteredModules] builds answer the same way whatever runs the guest.
 */
internal class BridgeConfigFormatPart(
    private val transport: BridgeTransport,
) {
    suspend fun cliCommands(): List<PluginCommandSpec> {
        val result = transport.call("cliCommands") ?: return emptyList()
        return BridgeCliCommands.runnable(BridgeDeserializer.parsePluginCommandSpecs(result), transport)
    }

    val fileExtensions: Set<String>
        get() {
            val result = transport.getProperty("configFormatExtensions")
            if (result.isEmpty()) return emptySet()
            return suspendCatching {
                BridgeDeserializer.json.decodeFromString<List<String>>(result).toSet()
            }.getOrDefault(emptySet())
        }

    /** The guest's parse result when it is a JSON object; anything else (absent, null, not an object) is `{}`. */
    suspend fun parseToJsonElement(content: String): JsonElement {
        val result = transport.call("configParse", content) ?: return EMPTY
        return suspendCatching { BridgeDeserializer.json.parseToJsonElement(result) as? JsonObject }.getOrNull() ?: EMPTY
    }

    /**
     * The guest's `extends` value; when `configParseExtends` is absent or answers null, the `extends` key of
     * [parseToJsonElement]. A result that is not JSON is the plain string (a guest returning a bare `base.yaml`).
     */
    suspend fun parseExtendsOnly(content: String): JsonElement? {
        val result = transport.call("configParseExtends", content)?.trim()
            ?: return (parseToJsonElement(content) as? JsonObject)?.get("extends")
        // an answer that is not JSON reads as no extends
        val parsed =
            try {
                BridgeDeserializer.json.parseToJsonElement(result)
            } catch (_: SerializationException) {
                null
            }
        return parsed.takeUnless { it is JsonNull }
    }

    private companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}
