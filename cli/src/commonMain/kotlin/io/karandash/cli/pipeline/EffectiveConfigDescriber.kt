package io.karandash.cli.pipeline

import io.karandash.cli.ai.AiHostBindings
import io.karandash.config.Config
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.security.TextRedactor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Renders the effective config as `path = value (source)` lines plus the AI provider chain.
 *
 * The source of a value is the root-most extends-chain entry that sets it (`config:<file>` for the
 * root, `extends:<url>` for an ancestor); anything no entry sets is `default` (or comes from the
 * active profile). Every line goes through [TextRedactor.redactForLog], so keys such as `apiKey`
 * or `token` and URL credentials never print in clear.
 */
internal object EffectiveConfigDescriber {
    fun describe(
        config: Config,
        layers: List<Pair<String, JsonElement>>,
    ): List<String> {
        val sources = layers.mapIndexed { index, (url, element) ->
            val label = if (index == layers.lastIndex) "config:$url" else "extends:$url"
            label to leaves(element)
                .keys
                .map(::normalizePath)
                .toSet()
        }
        val fallback = config.profile?.let { "default or profile:${it.id}" } ?: "default"
        val effective = KarandashJson.Default.encodeToJsonElement(Config.serializer(), config)
        val values = leaves(effective).map { (path, value) ->
            val key = filePath(normalizePath(path))
            val source = sources.lastOrNull { key in it.second }?.first?.let(TextRedactor::redactForLog) ?: fallback
            val line = "$path = $value"
            val safe = TextRedactor.redactForLog(line)
            // a redacted line keeps its path, so a secret still shows where it is set
            if (safe.startsWith(path)) "$safe ($source)" else "$path = [REDACTED] ($source)"
        }
        return values + AiHostBindings.describeAiChain(config.ai).map(TextRedactor::redactForLog)
    }

    /** Leaf values by dotted path; arrays and primitives are leaves, empty objects too. */
    private fun leaves(element: JsonElement): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()

        fun walk(
            path: String,
            node: JsonElement,
        ) {
            if (node is JsonObject && node.isNotEmpty()) {
                node.forEach { (key, child) -> walk(if (path.isEmpty()) key else "$path.$key", child) }
            } else {
                out[path] = node
            }
        }
        walk("", element)
        return out
    }

    private fun normalizePath(path: String): String = path.split('.').joinToString(".", transform = ConfigKeyNormalizer::kebabToCamelCase)

    /** `Config` splits rule settings into `rules.ruleConfigs` and `rules.ruleMeta` and renames `enrichment` to `enrichmentProviders`; files use `rules` and `enrichment`. */
    private fun filePath(path: String): String =
        RULE_SEGMENTS
            .firstOrNull { path.startsWith(it) }
            ?.let { "rules." + path.removePrefix(it) }
            ?: if (path.startsWith(ENRICHMENT_PROVIDERS)) "enrichment." + path.removePrefix(ENRICHMENT_PROVIDERS) else path

    private val RULE_SEGMENTS = listOf("rules.ruleConfigs.", "rules.ruleMeta.")
    private const val ENRICHMENT_PROVIDERS = "enrichmentProviders."
}
