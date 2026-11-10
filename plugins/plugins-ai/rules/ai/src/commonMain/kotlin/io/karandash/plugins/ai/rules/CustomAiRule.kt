package io.karandash.plugins.ai.rules

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A config-defined AI rule that runs without writing Kotlin code.
 *
 * Users declare custom rules in the plugin config under `customRules:`. Each rule
 * specifies a system prompt, severity, and AI call parameters. The rule ID must be
 * unique and is automatically prefixed with `"ai-"` if not already present.
 *
 * Config example:
 * ```yaml
 * customRules:
 *   - id: ai-changelog-check
 *     name: Changelog Check
 *     prompt: "Check if the MR updates CHANGELOG.md for user-visible changes."
 *     severity: warning
 *     maxTokens: 500
 *     temperature: 0.2
 * ```
 */
@Serializable
data class CustomAiRule(
    val id: String,
    val name: String,
    val prompt: String,
    val severity: String = "warning",
    val maxTokens: Int = 1000,
    val temperature: Double = 0.2,
    val enabled: Boolean = true,
    /** Rank in the per-MR budget plan; higher is funded first. Null keeps the default (0, below review-comments). */
    val priority: Int? = null,
) {
    companion object {
        /**
         * Parses custom rules from the `customRules` key of the plugin config.
         *
         * Returns only enabled rules. each entry is decoded on its own; an invalid one (missing `id` or
         * `prompt`, wrong type) is reported through [warn] and skipped, so it cannot disable the other rules.
         */
        fun fromPluginConfig(
            config: JsonElement,
            warn: (String) -> Unit = {},
        ): List<CustomAiRule> {
            val obj = config as? JsonObject ?: return emptyList()
            val arr = obj["customRules"] as? JsonArray ?: return emptyList()
            return arr
                .mapIndexedNotNull { index, entry -> decodeOrNull(index, entry, warn) }
                .map { if (it.id.startsWith("ai-")) it else it.copy(id = "ai-${it.id}") }
                .filter { it.enabled }
        }

        private fun decodeOrNull(
            index: Int,
            entry: JsonElement,
            warn: (String) -> Unit,
        ): CustomAiRule? =
            try {
                KarandashJson.Default.decodeFromJsonElement(serializer(), entry)
            } catch (e: SerializationException) {
                warn("AI plugin: customRules[$index] skipped: ${e.message?.lineSequence()?.firstOrNull()}")
                null
            } catch (e: IllegalArgumentException) {
                warn("AI plugin: customRules[$index] skipped: ${e.message?.lineSequence()?.firstOrNull()}")
                null
            }
    }
}
