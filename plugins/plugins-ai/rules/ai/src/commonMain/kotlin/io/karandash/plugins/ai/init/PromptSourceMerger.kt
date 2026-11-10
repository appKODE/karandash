@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.exception.KarandashException
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.prompts.PromptSource
import io.karandash.plugins.ai.prompts.RemotePromptFetcher
import io.karandash.sdk.content.ContentSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * Merges the three per-rule system prompt keys into one map, with precedence
 * `prompts` > `systemPrompts` > `promptOverrides`. A rule set under more than one key
 * logs a warn naming the key that wins.
 */
internal object PromptSourceMerger {
    private const val PROMPTS = "prompts"
    private const val SYSTEM_PROMPTS = "systemPrompts"
    private const val PROMPT_OVERRIDES = "promptOverrides"

    suspend fun merge(
        pluginCfg: AiPluginConfig,
        context: PluginContext,
        normalizeRuleId: (String) -> String,
    ): Map<String, PromptSource> {
        val layers = listOf(
            PROMPTS to pluginCfg.prompts.mapValues { (_, v) -> PromptSource.fromJson(v) },
            SYSTEM_PROMPTS to resolveSystemPrompts(pluginCfg.systemPrompts, context),
            PROMPT_OVERRIDES to pluginCfg.promptOverrides.mapValues { (_, v) -> PromptSource.Inline(v) },
        ).map { (key, sources) -> key to sources.mapKeys { (id, _) -> normalizeRuleId(id) } }

        val ruleIds = layers.flatMap { (_, sources) -> sources.keys }.toSet()
        return ruleIds.associateWith { id ->
            val owners = layers.filter { (_, sources) -> id in sources }
            if (owners.size > 1) {
                context.warn(
                    "AI plugin: system prompt for '$id' is set in ${owners.joinToString { "ai.${it.first}" }}; " +
                        "using ai.${owners.first().first}",
                )
            }
            checkNotNull(owners.first().second[id]) { "prompt source for '$id' vanished" }
        }
    }

    /** a fetcher for `url:` prompt sources, or null (no HTTP client) when no source has one. */
    fun remoteFetcher(
        sources: Collection<PromptSource>,
        context: PluginContext,
    ): RemotePromptFetcher? {
        if (sources.none(::hasUrl)) return null
        return RemotePromptFetcher(context.logger, context.allowLocalhost)
    }

    private fun hasUrl(source: PromptSource): Boolean =
        when (source) {
            is PromptSource.Url -> true
            is PromptSource.Layered -> source.sources.any(::hasUrl)
            is PromptSource.Pair -> hasUrl(source.system) || hasUrl(source.user)
            else -> false
        }

    /** Resolves each `systemPrompts` entry through the host (`file:` confined to the config dir, `url:` + `auth:`). */
    private suspend fun resolveSystemPrompts(
        entries: Map<String, JsonElement>,
        context: PluginContext,
    ): Map<String, PromptSource> =
        buildMap {
            for ((id, element) in entries) {
                resolveSystemPrompt(id, element, context)?.let { put(id, PromptSource.Inline(it)) }
            }
        }

    private suspend fun resolveSystemPrompt(
        id: String,
        element: JsonElement,
        context: PluginContext,
    ): String? =
        try {
            context.resolveContent(KarandashJson.Default.decodeFromJsonElement(ContentSource.serializer(), element))
        } catch (e: SerializationException) {
            skip(id, e, context)
        } catch (e: KarandashException) {
            skip(id, e, context)
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            skip(id, e, context)
        }

    private fun skip(
        id: String,
        e: Exception,
        context: PluginContext,
    ): String? {
        context.warn("AI plugin: ai.$SYSTEM_PROMPTS.$id skipped, builtin prompt kept: ${e.message}")
        return null
    }
}
