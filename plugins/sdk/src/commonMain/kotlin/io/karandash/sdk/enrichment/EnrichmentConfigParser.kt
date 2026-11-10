package io.karandash.sdk.enrichment

import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.utils.SuggestionEngine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Parser for nested enrichment provider sub-block configuration.
 *
 * Each enrichment provider block may contain per-ID sub-blocks alongside the
 * top-level reserved fields (`url`, `auth`, `token`, `enrich`, `cache`, `timeout`).
 * This parser extracts those sub-blocks, validates them against known IDs, and
 * emits warnings for unknown keys.
 *
 * Sub-block keys correspond to the portion of an enrichment ID after the prefix colon:
 * `jira:tickets` → sub-block key `tickets`; `jira:acceptance-criteria` → `acceptance-criteria`.
 */
public object EnrichmentConfigParser {
    private const val ENRICH_KEY = "enrich"

    /** Top-level keys that are provider configuration, not sub-block IDs. */
    public val RESERVED_KEYS: Set<String> = setOf("url", "auth", "token", ENRICH_KEY, "cache", "timeout", "enabled")

    /**
     * Extract sub-blocks from a provider config JsonObject.
     *
     * Sub-blocks are all non-reserved keys. Keys are matched in kebab-case, so the camelCase keys
     * produced by config normalization (`ticketKeys`) match kebab [knownSubKeys] (`ticket-keys`). Unknown sub-block keys (not in
     * [knownSubKeys]) are excluded from the result and added to [warnings].
     * The `enrich: [...]` list is a second way to enable sub-blocks: each entry (`jira:tickets` or `tickets`)
     * without a sub-block of its own enables that sub-block with defaults (`{ enabled: true }`); an explicit
     * sub-block always wins for its key.
     *
     * @param config The raw provider config object.
     * @param knownSubKeys Valid sub-block key names (e.g., `setOf("tickets", "acceptance-criteria")`).
     * @param providerConfigKey The provider config key used in warning messages (e.g., `"jira"`).
     * @param warnings Mutable list that receives human-readable warning strings.
     * @return Map of sub-block key → raw JsonElement for each known sub-block present.
     */
    public fun extractSubBlocks(
        config: JsonObject,
        knownSubKeys: Set<String>,
        providerConfigKey: String,
        warnings: MutableList<String>,
    ): Map<String, JsonElement> {
        val subBlocks = mutableMapOf<String, JsonElement>()

        for ((rawKey, value) in config) {
            val key = ConfigKeyNormalizer.camelToKebabCase(rawKey)
            if (key in RESERVED_KEYS) continue
            if (key in knownSubKeys) {
                subBlocks[key] = value
            } else {
                val suggestion = closestMatch(key, knownSubKeys)
                val hint = if (suggestion != null) " (did you mean '$suggestion'?)" else ""
                warnings += "enrichment.$providerConfigKey.$key: unknown sub-block key$hint"
            }
        }

        (config[ENRICH_KEY] as? JsonArray)?.forEach { entry ->
            val key = (entry as? JsonPrimitive)?.contentOrNull?.substringAfter(':')
            if (key != null && key in knownSubKeys && key !in subBlocks) {
                subBlocks[key] = buildJsonObject { put("enabled", true) }
            }
        }

        return subBlocks
    }

    /** All config warnings for a provider block: those of [extractSubBlocks] (unknown sub-block keys). */
    public fun configWarnings(
        config: JsonObject,
        knownSubKeys: Set<String>,
        providerConfigKey: String,
    ): List<String> = mutableListOf<String>().also { extractSubBlocks(config, knownSubKeys, providerConfigKey, it) }

    /**
     * Decodes a provider block with [serializer]: each [configWarnings] entry and an undecodable [config] go to
     * [warn]; the latter returns [default]'s value (`enrichment.<providerConfigKey>: invalid config, using defaults`).
     */
    public fun <T> decodeOrDefault(
        config: JsonElement,
        serializer: DeserializationStrategy<T>,
        knownSubKeys: Set<String>,
        providerConfigKey: String,
        warn: (String) -> Unit,
        default: () -> T,
    ): T {
        if (config is JsonObject) configWarnings(config, knownSubKeys, providerConfigKey).forEach(warn)
        val failure = try {
            return KarandashJson.Default.decodeFromJsonElement(serializer, config)
        } catch (e: SerializationException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        }
        warn("enrichment.$providerConfigKey: invalid config, using defaults: ${failure::class.simpleName}: ${failure.message}")
        return default()
    }

    /**
     * Return the closest match in [candidates] within 2 edit-distance steps, or `null`.
     */
    public fun closestMatch(
        key: String,
        candidates: Set<String>
    ): String? = SuggestionEngine.closest(key, candidates, maxDistance = 2)
}
