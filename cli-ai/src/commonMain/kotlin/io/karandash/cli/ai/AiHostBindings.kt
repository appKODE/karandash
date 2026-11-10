@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderSource
import io.karandash.api.run.RunSession
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.architecture.CrossMrDependencyAnalyzer
import io.karandash.plugins.ai.providers.RunAiProviderSource
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The AI-tier calls the `:cli` host makes: `:cli` reaches plugins-ai only through this module
 * (`cli` → `cli-ai`), so the build guard can ban `:cli` → `:plugins:plugins-ai:*`.
 */
object AiHostBindings {
    private const val KEY_TIMEOUT = "timeout"
    private const val KEY_MAX_RETRIES = "maxRetries"

    /** The run's [AiProviderSource]: providers resolved from the run's published `ai:` config. */
    fun providerSource(
        session: RunSession,
        registry: ModuleRegistry,
        contextFor: (JsonElement) -> PluginContext,
    ): AiProviderSource = RunAiProviderSource(session, registry, contextFor)

    /** Statistics directory for the `ai:` block (`ai.statistics.dir` or the default). */
    fun statsDir(aiConfig: JsonElement?): String = StatisticsStore.resolveDir(aiConfig)

    /** `config describe` lines: main provider, its fallback models and cache, named providers, consensus. */
    fun describeAiChain(ai: JsonElement?): List<String> {
        if (ai !is JsonObject) return listOf("ai chain: not configured")
        val cfg = try {
            AiPluginConfig.fromPluginConfig(ai)
        } catch (e: SerializationException) {
            return listOf("ai chain: unreadable (${e.message})")
        } catch (e: IllegalArgumentException) {
            return listOf("ai chain: unreadable (${e.message})")
        }
        val named = cfg.providers.entries
            .joinToString { (name, value) -> "$name=$value" }
            .ifEmpty { "none" }
        return listOf(
            "ai chain: main=${cfg.provider} model=${cfg.model ?: "provider default"} " +
                "fallback=${cfg.modelFallback.ifEmpty { "none" }} cache=response " +
                "timeout=${ai[KEY_TIMEOUT] ?: "provider default"} maxRetries=${ai[KEY_MAX_RETRIES] ?: "provider default"}",
            "ai chain: named providers: $named; rule providers: ${cfg.ruleProviders.ifEmpty { "none" }}",
            "ai chain: consensus=${cfg.consensusModels.ifEmpty { "off" }} strategy=${cfg.consensusStrategy} " +
                "analyzerConsensus=${cfg.analyzerConsensus}",
        )
    }

    /** `deps` report: file conflicts, ordering constraints and cycles between [current] and [siblings]. */
    fun dependencyReport(
        current: MergeRequest,
        siblings: List<MergeRequest>,
    ): String = CrossMrDependencyAnalyzer.formatReport(CrossMrDependencyAnalyzer.analyze(current, siblings))

    /** `deps --analyze-cross-mr` report: the full pairwise matrix over [mrs], siblings compared with each other too. */
    fun crossMrDependencyReport(mrs: List<MergeRequest>): String =
        CrossMrDependencyAnalyzer.formatReport(CrossMrDependencyAnalyzer.analyzeAll(mrs), showBothSides = true)
}
