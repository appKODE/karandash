package io.karandash.scripts

import io.karandash.api.config.parseConfigDuration
import io.karandash.api.exception.ConfigParseException
import io.karandash.duration.KarandashDuration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses raw `scripts:` config entries into typed [ScriptEntryConfig] objects.
 */
object ScriptConfigParser {
    /**
     * Parse a list of [JsonElement] entries into [ScriptEntryConfig] entries.
     *
     * Each element must be a [JsonObject] containing at least a `path` key. Optional keys:
     * - `mode`: "sandbox", "subprocess", or "auto" (default: "auto")
     * - `timeout`: milliseconds (default: 30000)
     * - `env`: list of env var names the script may read (default: absent = undeclared)
     *
     * @param raw List of [JsonElement] entries from the `scripts:` block.
     * @return List of parsed script entry configs.
     * @throws IllegalArgumentException if a required `path` field is missing.
     */
    fun parse(raw: List<JsonElement>): List<ScriptEntryConfig> = raw.map { entry -> parseSingle(entry) }

    private fun parseSingle(entry: JsonElement): ScriptEntryConfig {
        val obj = entry as? JsonObject
            ?: throw IllegalArgumentException("Script entry must be an object, got: $entry")

        val path = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Script entry missing required 'path' field")

        val mode = (obj["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
            ScriptMode.fromString(it)
        } ?: ScriptMode.AUTO

        val timeout = parseTimeout(obj["timeout"]) ?: ScriptEntryConfig.DEFAULT_TIMEOUT

        return ScriptEntryConfig(path = path, mode = mode, timeout = timeout, env = parseEnv(obj["env"]))
    }

    private fun parseEnv(element: JsonElement?): List<String>? {
        if (element == null || element is JsonNull) return null
        val names = (element as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (names == null || null in names) {
            throw ConfigParseException("Script entry 'env' must be a list of env var names, got: $element")
        }
        return names.filterNotNull()
    }

    /**
     * Parses a timeout value from a [JsonElement].
     *
     * Accepts either a numeric milliseconds value or a
     * human-readable duration string like `"30s"`, `"5m"`.
     *
     * @return Parsed [KarandashDuration], or null if the element is null/unparseable.
     */
    private fun parseTimeout(element: JsonElement?): KarandashDuration? {
        val primitive = element as? JsonPrimitive ?: return null
        val longValue = primitive.longOrNull
        if (longValue != null) return KarandashDuration.ofMillis(longValue)
        val strValue = primitive.takeIf { it.isString }?.content ?: return null
        return parseConfigDuration("timeout", strValue)
    }
}
