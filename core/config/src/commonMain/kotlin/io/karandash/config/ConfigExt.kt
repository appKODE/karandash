package io.karandash.config

import io.karandash.config.schema.ConfigSections
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement

/** The AI provider `ai.provider` names (e.g. `ai: { provider: "openai" }`), as written; `null` when unset. */
fun Config.aiProviderId(): String? = ((ai as? JsonObject)?.get("provider") as? JsonPrimitive)?.contentOrNull

/** Json instance that omits default-valued fields for compact round-trip output. */
private val compactJson = KarandashJson.Plain

/**
 * Converts this [Config] to a [JsonElement] tree suitable for re-parsing or serialization.
 *
 * Produces the same structure as the raw YAML/JSON config. Uses kotlinx.serialization
 * for nested `@Serializable` types (ProfileRef, EnrichmentConfig, RuleExecutionMeta)
 * instead of manual map construction.
 */
fun Config.toJsonElement(): JsonElement {
    val entries = buildMap {
        rulesToJsonElement()?.let { put(ConfigSections.RULES, it) }
        platforms?.let { put(ConfigSections.PLATFORMS, JsonObject(it)) }
        reporters?.let { put(ConfigSections.REPORTERS, JsonObject(it)) }
        scripts?.let { put(ConfigSections.SCRIPTS, compactJson.encodeToJsonElement(it)) }
        profile?.let { put(ConfigSections.PROFILE, compactJson.encodeToJsonElement(ProfileRef.serializer(), it)) }
        aiAndEnrichmentToJsonElement()?.let { put(ConfigSections.AI, it) }
        dangerfile?.let { put(ConfigSections.DANGERFILE, JsonPrimitive(it)) }
        baseline?.let { put(ConfigSections.BASELINE, JsonPrimitive(it)) }
        if (dryRun) put("dryRun", JsonPrimitive(true))
        dryRunScope?.let { put("dryRunScope", JsonPrimitive(it.value)) }
        if (allowLocalhost) put("allowLocalhost", JsonPrimitive(true))
        if (allowPrivateNetwork) put("allowPrivateNetwork", JsonPrimitive(true))
        policies?.let { put(ConfigSections.POLICIES, compactJson.encodeToJsonElement(it)) }
        timeouts?.let { put(ConfigSections.TIMEOUTS, compactJson.encodeToJsonElement(TimeoutConfig.serializer(), it)) }
        if (http != HttpConfig()) put(ConfigSections.HTTP, compactJson.encodeToJsonElement(HttpConfig.serializer(), http))
        if (limits != LimitsConfig()) put(ConfigSections.LIMITS, compactJson.encodeToJsonElement(LimitsConfig.serializer(), limits))
        if (display != DisplayConfig()) put(ConfigSections.DISPLAY, compactJson.encodeToJsonElement(DisplayConfig.serializer(), display))
        if (report != ReportConfig()) put(ConfigSections.REPORT, compactJson.encodeToJsonElement(ReportConfig.serializer(), report))
        if (security !=
            SecurityConfig()
        ) {
            put(ConfigSections.SECURITY, compactJson.encodeToJsonElement(SecurityConfig.serializer(), security))
        }
    }
    return JsonObject(entries)
}

private fun Config.rulesToJsonElement(): JsonElement? {
    val entries = buildMap<String, JsonElement> {
        for ((ruleId, ruleConfig) in rules.ruleConfigs) {
            val meta = rules.ruleMeta[ruleId]
            put(ruleId, if (meta != null) mergeMetaIntoConfig(ruleConfig, meta) else ruleConfig)
        }
    }
    return if (entries.isNotEmpty()) JsonObject(entries) else null
}

/**
 * Merges [RuleExecutionMeta] fields back into a rule config [JsonElement] for round-trip serialization.
 * Only non-default meta values are written, using the serializer with [encodeDefaults] = false.
 */
private fun mergeMetaIntoConfig(
    ruleConfig: JsonElement,
    meta: RuleExecutionMeta,
): JsonElement {
    val ruleObj = ruleConfig as? JsonObject ?: JsonObject(emptyMap())
    val metaObj = compactJson.encodeToJsonElement(RuleExecutionMeta.serializer(), meta) as? JsonObject
        ?: return ruleConfig
    return JsonObject(ruleObj + metaObj)
}

private fun Config.aiAndEnrichmentToJsonElement(): JsonElement? {
    val enrichmentElement = enrichment?.let { enrich ->
        val obj = compactJson.encodeToJsonElement(EnrichmentConfig.serializer(), enrich) as? JsonObject
        obj?.takeIf { it.isNotEmpty() }
    }
    if (ai == null && enrichmentElement == null) return null
    return JsonObject(
        buildMap {
            (ai as? JsonObject)?.let { putAll(it) }
            enrichmentElement?.let { put("enrichment", it) }
        },
    )
}
