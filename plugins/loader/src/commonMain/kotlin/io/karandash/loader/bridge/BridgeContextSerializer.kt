package io.karandash.loader.bridge

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.patterns.PatternType
import io.karandash.patterns.detectPatternType
import io.karandash.sdk.context.BridgeHostSettings
import io.karandash.utils.mr.ShortSha
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializes [PluginContext] to JSON for bridge transport: the one context shape every guest transport receives.
 */
internal object BridgeContextSerializer {
    private val json = KarandashJson.Default

    fun serialize(
        context: PluginContext,
        resolvedPluginConfig: JsonElement = context.pluginConfig
    ): String = toJsonObject(context, resolvedPluginConfig).toString()

    /** The [ReportContext] an AI report or metric guest receives: findings, MR, config and history. */
    fun serializeReport(context: ReportContext): String =
        buildJsonObject {
            put("findings", json.encodeToJsonElement(ListSerializer(Finding.serializer()), context.findings))
            put("mr", json.encodeToJsonElement(MergeRequest.serializer(), context.mr))
            put("config", context.config)
            put("history", JsonArray(context.history))
        }.toString()

    /** The guest side of [serializeReport]: the [ReportContext] a JAR report or metric module receives. */
    fun deserializeReport(value: String): ReportContext {
        val context = json.parseToJsonElement(value.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        return ReportContext(
            findings = json.decodeFromJsonElement(ListSerializer(Finding.serializer()), context["findings"] ?: JsonArray(emptyList())),
            mr = json.decodeFromJsonElement(MergeRequest.serializer(), context["mr"] ?: JsonNull),
            enrichment = EnrichmentContext.EMPTY,
            config = context["config"] ?: JsonNull,
            history = (context["history"] as? JsonArray).orEmpty(),
        )
    }

    /**
     * The context every guest transport receives: config, host version, working and config dirs, `allowLocalhost`,
     * host display settings, resolved named patterns and the env [GuestEnv] lets through.
     */
    fun toJsonObject(
        context: PluginContext,
        resolvedPluginConfig: JsonElement = context.pluginConfig
    ): JsonObject =
        buildJsonObject {
            put("pluginConfig", resolvedPluginConfig)
            put("hostVersion", JsonPrimitive(context.hostVersion))
            put("workingDirectory", JsonPrimitive(context.workingDirectory))
            put("configDir", JsonPrimitive(context.configDir))
            put("allowLocalhost", JsonPrimitive(context.allowLocalhost))
            put(BridgeHostSettings.SHORT_SHA_LENGTH, JsonPrimitive(ShortSha.length))
            put(PATTERNS, resolvedPatterns(context, resolvedPluginConfig))
            put(ENV, JsonObject(GuestEnv.filter(context::env).mapValues { JsonPrimitive(it.value) }))
        }

    /** Key of the `{name: value}` env map ([GuestEnv]). */
    const val ENV = "env"

    /** Key of the `{pattern: {source, options}}` map of named patterns the host resolved for the guest. */
    const val PATTERNS = BridgeHostSettings.PATTERNS
    const val SOURCE = BridgeHostSettings.PATTERN_SOURCE
    const val OPTIONS = BridgeHostSettings.PATTERN_OPTIONS

    /** At most this many named patterns travel in one guest context. */
    const val MAX_PATTERNS = 512

    /** At most this many regex source characters, summed over the named patterns, travel in one guest context. */
    const val MAX_PATTERN_CHARS = 256 * 1024

    /**
     * The `@name` patterns in [config], then the rest of the host's named-pattern table
     * ([PluginContext.namedPatternNames]), resolved by the host: named patterns come from the host's registry (core,
     * module and user `patterns:`), which a bridged guest cannot reach, so their compiled form travels in the context,
     * up to [MAX_PATTERNS] entries and [MAX_PATTERN_CHARS] source characters (config references go first).
     */
    private fun resolvedPatterns(
        context: PluginContext,
        config: JsonElement,
    ): JsonObject {
        val entries = mutableMapOf<String, JsonElement>()
        var chars = 0
        for (pattern in namedPatterns(config) + context.namedPatternNames()) {
            if (entries.size >= MAX_PATTERNS) break
            if (pattern in entries) continue
            val regex = context.resolvePattern(pattern)
            if (chars + regex.pattern.length > MAX_PATTERN_CHARS) continue
            chars += regex.pattern.length
            entries[pattern] =
                buildJsonObject {
                    put(SOURCE, JsonPrimitive(regex.pattern))
                    put(OPTIONS, JsonArray(regex.options.map { JsonPrimitive(it.name) }))
                }
        }
        return JsonObject(entries)
    }

    private fun namedPatterns(element: JsonElement): Set<String> =
        when (element) {
            is JsonObject -> element.values.flatMapTo(linkedSetOf()) { namedPatterns(it) }
            is JsonArray -> element.flatMapTo(linkedSetOf()) { namedPatterns(it) }
            is JsonPrimitive -> setOfNotNull(element.takeIf { it.isString && detectPatternType(it.content) == PatternType.NAMED }?.content)
        }
}
