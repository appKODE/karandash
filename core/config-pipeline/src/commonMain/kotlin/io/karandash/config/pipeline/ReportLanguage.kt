package io.karandash.config.pipeline

import io.karandash.config.ReportConfig
import io.karandash.config.schema.ConfigSections
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves `report.language` from a normalized config: `report.language` wins, else its
 * `ai.responseLanguage` alias, else [ReportConfig.DEFAULT_LANGUAGE]; a blank value counts as unset. Across `extends:`
 * layers [applyLayerPrecedence] decides first. The AI rules read `ai.responseLanguage`, so a
 * set `report.language` is copied there.
 */
object ReportLanguage {
    private const val LANGUAGE = "language"
    private const val ALIAS = "responseLanguage"

    /** [block] (the decoded `report:` block of the normalized [root]) with its language resolved. */
    fun resolve(
        root: JsonObject,
        block: ReportConfig = ReportConfig(),
    ): ReportConfig {
        val language = canonical(root) ?: alias(root) ?: return block.copy(language = ReportConfig.DEFAULT_LANGUAGE)
        return block.copy(language = ReportConfig.normalizeLanguage(language))
    }

    /** [ai] with `responseLanguage` set to [report]'s language when the user set `report.language`; else unchanged. */
    fun applyToAi(
        root: JsonObject,
        ai: JsonElement?,
        report: ReportConfig,
    ): JsonElement? = if (ai is JsonObject && canonical(root) != null) JsonObject(ai + (ALIAS to JsonPrimitive(report.language))) else ai

    /**
     * [merged] with the language precedence decided per `extends:` layer: the nearest layer (root first) that sets
     * either key wins, and within one layer `report.language` beats the alias. The winner's value is written back, so a
     * blank value in a nearer layer never hides it; when the winner sets only the alias, a parent's `report.language`
     * is dropped so the alias resolves.
     */
    fun applyLayerPrecedence(
        merged: JsonElement,
        layers: List<JsonElement>,
    ): JsonElement {
        val winner = layers.lastOrNull { it is JsonObject && (canonical(it) != null || alias(it) != null) } as? JsonObject
        if (winner == null || merged !is JsonObject) return merged
        val report = merged[ConfigSections.REPORT] as? JsonObject ?: JsonObject(emptyMap())
        val language = canonical(winner)
        if (language != null) return merged.with(ConfigSections.REPORT, JsonObject(report + (LANGUAGE to JsonPrimitive(language))))
        val ai = merged[ConfigSections.AI] as? JsonObject ?: JsonObject(emptyMap())
        return merged
            .with(ConfigSections.REPORT, JsonObject(report - LANGUAGE))
            .with(ConfigSections.AI, JsonObject(ai + (ALIAS to JsonPrimitive(checkNotNull(alias(winner)) { "winner sets a key" }))))
    }

    private fun JsonObject.with(
        section: String,
        value: JsonObject,
    ): JsonObject = JsonObject(this + (section to value))

    /** The precedence warning when both keys are set; the alias alone works silently. */
    fun warnings(layers: List<JsonElement>): List<String> = layers.flatMap(::layerWarnings).distinct()

    private fun layerWarnings(root: JsonElement): List<String> {
        val obj = root as? JsonObject ?: return emptyList()
        val alias = alias(obj) ?: return emptyList()
        val canonical = canonical(obj) ?: return emptyList()
        return listOf("Both 'report.language' ($canonical) and its alias 'ai.response-language' ($alias) are set: report.language wins.")
    }

    private fun canonical(root: JsonObject): String? = root.string(ConfigSections.REPORT, LANGUAGE)

    private fun alias(root: JsonObject): String? = root.string(ConfigSections.AI, ALIAS)

    private fun JsonObject.string(
        section: String,
        key: String,
    ): String? = ((this[section] as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
