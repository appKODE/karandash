package io.karandash.plugins.ai.core.config

import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Rewrites the documented short forms of AI config keys into the shapes [io.karandash.plugins.ai.core.AiPluginConfig]
 * reads, before deserialization. Both forms stay valid; the long form wins on a name clash.
 *
 * - `macros: { name: [ { rule, ...settings } ] }` → `macroDefinitions: { name: { rules: { rule: settings } } }`
 * - `applyMacros: [ name ]` → `macroReferences: [ { whenFilesMatch: ["**"], useMacro: name } ]`
 * - `environmentTracks: [ { targetBranch, rules } ]` → `tracks: [ { targetBranchPattern, ruleOverrides, disableRules } ]`
 * - `consensus: { models, strategy }` → `consensusModels`, `consensusStrategy`
 */
object AiConfigAliases {
    private const val KEY_MACROS = "macros"
    private const val KEY_APPLY_MACROS = "applyMacros"
    private const val KEY_MACRO_DEFINITIONS = "macroDefinitions"
    private const val KEY_MACRO_REFERENCES = "macroReferences"
    private const val KEY_ENVIRONMENT_TRACKS = "environmentTracks"
    private const val KEY_TRACKS = "tracks"
    private const val KEY_CONSENSUS = "consensus"
    private const val KEY_CONSENSUS_MODELS = "consensusModels"
    private const val KEY_CONSENSUS_STRATEGY = "consensusStrategy"
    private const val KEY_RULE = "rule"
    private const val KEY_RULES = "rules"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET_BRANCH = "targetBranch"
    private const val ALL_FILES = "**"

    /** Alias keys this rewrite consumes; they are top-level AI keys, not analyzer blocks. */
    val KEYS: Set<String> = setOf(KEY_MACROS, KEY_APPLY_MACROS, KEY_ENVIRONMENT_TRACKS)

    /** Returns [config] with every alias key rewritten into its long form. */
    fun normalize(config: JsonObject): JsonObject {
        if (KEYS.none { it in config } && KEY_CONSENSUS !in config) return config
        val result = config.toMutableMap()
        (config[KEY_CONSENSUS] as? JsonObject)?.let { consensus ->
            consensus["models"]?.let { if (KEY_CONSENSUS_MODELS !in result) result[KEY_CONSENSUS_MODELS] = it }
            consensus["strategy"]?.let { if (KEY_CONSENSUS_STRATEGY !in result) result[KEY_CONSENSUS_STRATEGY] = it }
        }
        (config[KEY_MACROS] as? JsonObject)?.let { macros ->
            val existing = config[KEY_MACRO_DEFINITIONS] as? JsonObject ?: JsonObject(emptyMap())
            result[KEY_MACRO_DEFINITIONS] = JsonObject(macros.mapValues { (_, v) -> macroDefinition(v) } + existing)
        }
        (config[KEY_APPLY_MACROS] as? JsonArray)?.let { names ->
            val existing = config[KEY_MACRO_REFERENCES] as? JsonArray ?: JsonArray(emptyList())
            result[KEY_MACRO_REFERENCES] = JsonArray(existing + names.mapNotNull(::macroReference))
        }
        (config[KEY_ENVIRONMENT_TRACKS] as? JsonArray)?.let { tracks ->
            val existing = config[KEY_TRACKS] as? JsonArray ?: JsonArray(emptyList())
            result[KEY_TRACKS] = JsonArray(existing + tracks.mapNotNull { (it as? JsonObject)?.let(::track) })
        }
        KEYS.forEach(result::remove)
        return JsonObject(result)
    }

    /** A list of `{ rule, ...settings }` becomes `{ rules: { rule: settings } }`; an object passes through. */
    private fun macroDefinition(value: JsonElement): JsonElement {
        val entries = value as? JsonArray ?: return value
        val rules = entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val rule = (obj[KEY_RULE] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            rule to JsonObject(obj - KEY_RULE)
        }
        return JsonObject(mapOf(KEY_RULES to JsonObject(rules.toMap())))
    }

    private fun macroReference(name: JsonElement): JsonElement? {
        val macro = (name as? JsonPrimitive)?.contentOrNull ?: return null
        return JsonObject(
            mapOf("whenFilesMatch" to JsonArray(listOf(JsonPrimitive(ALL_FILES))), "useMacro" to JsonPrimitive(macro)),
        )
    }

    /**
     * `{ targetBranch: "main|master", rules: { r: { enabled: false } } }` becomes a track whose pattern matches
     * the whole branch name, whose `ruleOverrides` are the rule settings and whose `disableRules` lists the
     * rules set to `enabled: false`.
     */
    private fun track(obj: JsonObject): JsonElement? {
        val branch = (obj[KEY_TARGET_BRANCH] as? JsonPrimitive)?.contentOrNull ?: return null
        val rules = obj[KEY_RULES] as? JsonObject ?: JsonObject(emptyMap())
        val disabled = rules
            .filterValues { ((it as? JsonObject)?.get(KEY_ENABLED) as? JsonPrimitive)?.booleanOrNull == false }
            .keys
            .map { JsonPrimitive(ConfigKeyNormalizer.camelToKebabCase(it)) }
        val converted = obj - KEY_TARGET_BRANCH - KEY_RULES + mapOf(
            "name" to (obj["name"] ?: JsonPrimitive(branch)),
            "targetBranchPattern" to JsonPrimitive("^(?:$branch)$"),
            "ruleOverrides" to rules,
            "disableRules" to JsonArray(disabled),
        )
        return JsonObject(converted)
    }
}
