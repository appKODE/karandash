package io.karandash.loader

import io.karandash.api.ModuleType
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared utilities for plugin bridge implementations (JS, WASM, Native).
 *
 * Parsing and serialization logic shared by [io.karandash.loader.js.JsModuleBridge],
 * [io.karandash.loader.wasm.WasmModuleBridge], the JVM `NativeModuleBridge` and
 * [io.karandash.loader.bridge.TransportModuleBridge] (native dlopen and JAR bridge guests on the native host).
 */
internal object BridgeUtils {
    /**
     * Parses a raw module type string into [ModuleType].
     *
     * The input is trimmed, uppercased, and hyphens are replaced with underscores before matching.
     *
     * @param rawType The raw type string from a plugin bridge.
     * @param bridgeLabel Label used in the error message (e.g. "WASM", "native", "JS").
     * @return The parsed [ModuleType].
     * @throws IllegalArgumentException if the type string is not recognised.
     */
    fun parseModuleType(
        rawType: String,
        bridgeLabel: String = "plugin"
    ): ModuleType =
        try {
            ModuleType.fromString(rawType)
        } catch (e: IllegalArgumentException) {
            val detail = e.message?.removePrefix("Unsupported ") ?: "module type '$rawType'"
            throw IllegalArgumentException("Unsupported $bridgeLabel $detail")
        }

    /**
     * Parses a [JsonObject] into a [ConfigProperty].
     *
     * @param obj The JSON object containing config property fields.
     * @param logWarning Callback invoked when an unknown [ConfigPropertyType] is encountered.
     * @return The parsed [ConfigProperty].
     */
    fun parseConfigProperty(
        obj: JsonObject,
        logWarning: (Exception) -> Unit = {},
    ): ConfigProperty {
        val typeName = obj["type"]?.jsonPrimitive?.content ?: "STRING"
        val propType = try {
            ConfigPropertyType.valueOf(typeName.uppercase())
        } catch (e: IllegalArgumentException) {
            logWarning(e)
            ConfigPropertyType.STRING
        }
        return ConfigProperty(
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            type = propType,
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            required = obj["required"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            defaultValue = obj["defaultValue"]?.let { JsonUtils.jsonElementToAny(it) },
            enumValues = (obj["enumValues"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content },
            deprecated = obj["deprecated"]?.jsonPrimitive?.content,
        )
    }

    /**
     * Parses a [JsonObject] into a [CIEnvironment].
     *
     * @param obj The JSON object containing CI environment fields.
     * @return The parsed [CIEnvironment].
     */
    fun parseCIEnvironment(obj: JsonObject): CIEnvironment =
        CIEnvironment(
            platform = obj["platform"]?.jsonPrimitive?.contentOrNull ?: "",
            // JSON null (a JAR guest encodes absent values explicitly) stays null, not the string "null".
            projectId = obj["projectId"]?.jsonPrimitive?.contentOrNull,
            mrId = obj["mrId"]?.jsonPrimitive?.contentOrNull,
            token = obj["token"]?.jsonPrimitive?.contentOrNull,
            baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull,
        )

    /**
     * Parses a [JsonObject] into a [ParsedMergeRequestUrl].
     *
     * @param obj The JSON object containing parsed MR URL fields.
     * @return The parsed [ParsedMergeRequestUrl].
     */
    fun parseParsedMergeRequestUrl(obj: JsonObject): ParsedMergeRequestUrl =
        ParsedMergeRequestUrl(
            platform = obj["platform"]?.jsonPrimitive?.content ?: "",
            baseUrl = obj["baseUrl"]?.jsonPrimitive?.content ?: "",
            projectId = obj["projectId"]?.jsonPrimitive?.content ?: "",
            mrId = obj["mrId"]?.jsonPrimitive?.content ?: "",
        )
}
