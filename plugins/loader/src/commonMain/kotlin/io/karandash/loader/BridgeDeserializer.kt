package io.karandash.loader

import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.config.ConfigProperty
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.support.suspendCatching
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared JSON deserialisation helpers for plugin bridge implementations (JS, WASM, Native).
 *
 * All methods accept a raw JSON string and return the parsed domain object or a safe default
 * on failure. Bridge-specific logic (engine calls, capability checks) stays in each bridge.
 */
internal object BridgeDeserializer {
    internal val json = io.karandash.models.support.KarandashJson.Default

    /**
     * Parses a JSON object string into [AiCompletionResponse].
     * Token counts accept any JSON number (JS bridges may send `3.0` for an integer).
     *
     * @param jsonStr Raw JSON object string from a plugin bridge.
     * @return Parsed response, or a zeroed-out response on failure.
     */
    fun parseAiCompletionResponse(jsonStr: String): AiCompletionResponse {
        val obj = suspendCatching { json.parseToJsonElement(jsonStr) }.getOrNull()
        if (obj !is JsonObject) return AiCompletionResponse("", 0, 0, 0, "")
        return AiCompletionResponse(
            content = obj.stringField("content"),
            promptTokens = obj.numberField("promptTokens") ?: 0,
            completionTokens = obj.numberField("completionTokens") ?: 0,
            totalTokens = obj.numberField("totalTokens") ?: 0,
            model = obj.stringField("model"),
        )
    }

    /**
     * Parses a JSON object string into a [Map]<[String], [JsonElement]>.
     * Returns an empty map if the string is not a valid JSON object.
     *
     * @param jsonStr Raw JSON object string from a plugin bridge.
     */
    fun parseJsonElementMap(jsonStr: String): Map<String, JsonElement> =
        suspendCatching {
            val obj = json.parseToJsonElement(jsonStr)
            if (obj is JsonObject) obj.toMap() else emptyMap()
        }.getOrDefault(emptyMap())

    /**
     * Parses a JSON object string into a [Map]<[String], [String]>.
     * Non-string values are converted via [Any.toString]. Returns an empty map on failure.
     *
     * @param jsonStr Raw JSON object string from a plugin bridge.
     */
    fun parseStringMap(jsonStr: String): Map<String, String> {
        val obj = suspendCatching { json.parseToJsonElement(jsonStr) }.getOrNull()
        if (obj !is JsonObject) return emptyMap()
        return obj.mapValues { (_, v) -> if (v is JsonPrimitive) v.contentOrNull.orEmpty() else v.toString() }
    }

    /**
     * Parses a JSON object string into a [Map]<[String], [Int]>.
     * Entries whose values cannot be coerced to [Int] are dropped. Returns an empty map on failure.
     *
     * @param jsonStr Raw JSON object string from a plugin bridge.
     */
    fun parseIntMap(jsonStr: String): Map<String, Int> {
        val obj = suspendCatching { json.parseToJsonElement(jsonStr) }.getOrNull()
        if (obj !is JsonObject) return emptyMap()
        return obj.keys.mapNotNull { k -> obj.numberField(k)?.let { k to it } }.toMap()
    }

    /**
     * Parses a JSON array string into a list of [ConfigProperty].
     * Delegates per-element parsing to [BridgeUtils.parseConfigProperty].
     * Skips elements that are not JSON objects. Returns an empty list on failure.
     *
     * @param jsonStr Raw JSON array string from a plugin bridge.
     */
    fun parseConfigProperties(jsonStr: String): List<ConfigProperty> =
        suspendCatching {
            val arr = json.parseToJsonElement(jsonStr)
            if (arr !is JsonArray) return emptyList()
            arr.mapNotNull { elem ->
                if (elem !is JsonObject) {
                    null
                } else {
                    BridgeUtils.parseConfigProperty(elem)
                }
            }
        }.getOrDefault(emptyList())

    /**
     * Parses a JSON object string into a [io.karandash.api.platform.CIEnvironment].
     * Returns `null` if the string is not a valid JSON object or is missing the required
     * `platform` field.
     *
     * @param jsonStr Raw JSON object string from a plugin bridge.
     */
    fun parseCIEnvironmentFromJson(jsonStr: String): io.karandash.api.platform.CIEnvironment? =
        suspendCatching {
            val obj = json.parseToJsonElement(jsonStr)
            if (obj !is JsonObject) return null
            if (obj["platform"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) return null
            BridgeUtils.parseCIEnvironment(obj)
        }.getOrNull()

    /**
     * Parses a JSON object string into a [ParsedMergeRequestUrl].
     * Returns `null` if the string is not a valid JSON object or is missing required fields.
     *
     * @param jsonStr Raw JSON object string from a plugin bridge.
     */
    fun parseParsedMergeRequestUrlFromJson(jsonStr: String): ParsedMergeRequestUrl? =
        suspendCatching {
            val obj = json.parseToJsonElement(jsonStr)
            if (obj !is JsonObject) return null
            val platform = obj["platform"]?.jsonPrimitive?.content ?: return null
            val baseUrl = obj["baseUrl"]?.jsonPrimitive?.content ?: return null
            val projectId = obj["projectId"]?.jsonPrimitive?.content ?: return null
            val mrId = obj["mrId"]?.jsonPrimitive?.content ?: return null
            ParsedMergeRequestUrl(platform = platform, baseUrl = baseUrl, projectId = projectId, mrId = mrId)
        }.getOrNull()

    /**
     * Parses a JSON array string into a list of [PluginCommandSpec], `subcommands` included.
     * Skips elements that are not JSON objects or are missing a `name` field.
     * Returns an empty list on failure.
     *
     * @param jsonStr Raw JSON array string from a plugin bridge.
     */
    fun parsePluginCommandSpecs(jsonStr: String): List<PluginCommandSpec> =
        suspendCatching { commandSpecs(json.parseToJsonElement(jsonStr)) }.getOrDefault(emptyList())

    private fun commandSpecs(list: JsonElement?): List<PluginCommandSpec> =
        (list as? JsonArray).orEmpty().mapNotNull { element ->
            if (element !is JsonObject) return@mapNotNull null
            val specName = (element["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val help = (element["help"] as? JsonPrimitive)?.content ?: ""
            PluginCommandSpec(name = specName, help = help, subcommands = commandSpecs(element["subcommands"]))
        }

    private fun JsonObject.stringField(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.numberField(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toInt()
}
