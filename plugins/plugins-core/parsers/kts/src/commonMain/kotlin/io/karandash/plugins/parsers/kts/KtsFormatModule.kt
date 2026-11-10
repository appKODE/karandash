package io.karandash.plugins.parsers.kts

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.SchemaAwareFormatModule
import io.karandash.parser.KarandashParser
import io.karandash.parser.RuleSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Kotlin DSL (.kts) config format module using KarandashParser.
 *
 * KTS is parsed into the same JSON tree a YAML config produces ([KarandashParser.parseToJson]), so both
 * formats share one interpretation in the config pipeline. A top-level `extends = ...` takes part in
 * extends chains like the YAML key (the default [ConfigFormatModule.parseExtendsOnly] reads it).
 */
class KtsFormatModule private constructor(
    private val ruleSchemas: Map<String, RuleSchema>,
) : SchemaAwareFormatModule {
    constructor() : this(emptyMap())

    companion object {
        const val FORMAT_ID = "kts"
        const val PRIORITY = 0
    }

    override val formatId: String = FORMAT_ID
    override val fileExtensions: Set<String> = setOf("kts")
    override val priority: Int = PRIORITY

    /** Returns a new module that type-checks rule blocks against [schemas] (rule ID to JSON Schema). */
    @ExperimentalKarandashApi
    override fun withRuleSchemas(schemas: JsonObject): ConfigFormatModule =
        KtsFormatModule(
            schemas.mapNotNull { (id, schema) -> (schema as? JsonObject)?.let { id to RuleSchema.fromJsonSchema(it) } }.toMap(),
        )

    /**
     * Parse KTS content into a [JsonElement] tree for two-phase config loading.
     *
     * Env var references (`env.VAR`) are emitted as `"${VAR}"` placeholder strings via
     * [KarandashParser.parseToJson]. The placeholders are resolved in phase 2 by the config pipeline.
     *
     * @param content The KTS content as a string.
     * @return [JsonElement] tree with `"${VAR}"` placeholders for env var references.
     * @throws IllegalArgumentException if the KTS is invalid (syntax error or a construct with no config meaning).
     */
    override suspend fun parseToJsonElement(content: String): JsonElement {
        if (content.isBlank()) return JsonObject(emptyMap())
        return try {
            KarandashParser(ruleSchemas = ruleSchemas).parseToJson(content)
        } catch (e: ConfigParseException) {
            throw IllegalArgumentException("Invalid KTS config: ${e.message}", e)
        }
    }
}
