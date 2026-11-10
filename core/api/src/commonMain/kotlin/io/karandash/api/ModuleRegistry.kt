package io.karandash.api

import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.api.module.TranslationModule
import io.karandash.api.run.DefaultRunSession
import io.karandash.api.run.RunSession
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger

/**
 * Registry of all loaded modules.
 *
 * Provides generic lookup by type via [getByType], plus indexed lookups for
 * rules ([findRuleHandler]) and provider aliases ([findByAlias]).
 *
 * Typed accessors have default implementations using [getByType] + [filterIsInstance].
 * [ModuleRegistryImpl] may override for optimized lookups.
 */
@OptIn(ExperimentalKarandashApi::class)
public interface ModuleRegistry : ModuleLookup {
    public suspend fun register(module: Module)

    /**
     * Registers one guest that serves several [ModuleType]s as a single entry.
     *
     * [modules] share one id and hold one part per type; the first is the primary part that [get] returns.
     * [getByType] returns every part for its type, rule and alias indexes cover every part, and [unregister]
     * removes them all. The default registers only the primary part, which is what registries written before
     * composites did.
     */
    public suspend fun registerComposite(modules: List<Module>) {
        register(modules.first())
    }

    public suspend fun unregister(id: String): Boolean

    // Read-only members are redeclared from [ModuleLookup] so the JVM `DefaultImpls`
    // signatures that already-compiled implementers link against stay in place.

    override fun get(id: String): Module?

    override fun getByType(type: ModuleType): List<Module>

    override fun registeredIds(): Set<String>

    override fun hasRule(ruleId: String): Boolean

    override fun findRuleHandler(ruleId: String): RuleModule?

    /**
     * Returns the canonical (original) rule ID for a given lookup key.
     */
    override fun canonicalRuleId(ruleId: String): String = ruleId

    override fun findByAlias(alias: String): PlatformServiceModule?

    override fun allAliases(): Map<String, PlatformServiceModule>

    override fun aliasCollisions(): List<String>

    /**
     * Opens a session for one validation run. Per-run module handles ([Module.newHandle]) are
     * resolved, tracked and shut down through it; the caller must [RunSession.close] it.
     */
    @ExperimentalKarandashApi
    public suspend fun forRun(): RunSession = forRun(NoOpLogger)

    /** [forRun] with a [logger] for handle and teardown warnings. */
    @ExperimentalKarandashApi
    public suspend fun forRun(logger: Logger): RunSession = DefaultRunSession(this, logger)

    // --- Typed accessors with default implementations ---

    public fun ruleModules(): List<RuleModule> = getByType(ModuleType.RULE).filterIsInstance<RuleModule>()

    public fun platformServices(): List<PlatformServiceModule> =
        getByType(ModuleType.PLATFORM_SERVICE).filterIsInstance<PlatformServiceModule>()

    public fun reporters(): List<ReporterModule> = getByType(ModuleType.REPORTER).filterIsInstance<ReporterModule>()

    public fun formats(): List<FormatModule> = getByType(ModuleType.FORMAT).filterIsInstance<FormatModule>()

    public fun outputs(): List<OutputModule> = getByType(ModuleType.OUTPUT).filterIsInstance<OutputModule>()

    public fun scriptModules(): List<ScriptModule> = getByType(ModuleType.SCRIPT).filterIsInstance<ScriptModule>()

    public fun cliCommandModules(): List<CliCommandModule> = getByType(ModuleType.CLI_COMMAND).filterIsInstance<CliCommandModule>()

    /** The language packs ([TranslationModule]s) of the run. */
    public fun translations(): List<TranslationModule> = getByType(ModuleType.TRANSLATION).filterIsInstance<TranslationModule>()

    /** Config-file format parsers ([ModuleType.CONFIG_FORMAT]). */
    public fun configFormats(): List<ConfigFormatModule> = getByType(ModuleType.CONFIG_FORMAT).filterIsInstance<ConfigFormatModule>()

    public fun aiProviders(): List<AiProviderModule> = getByType(ModuleType.AI_PROVIDER).filterIsInstance<AiProviderModule>()

    public fun findAiProvider(providerId: String): AiProviderModule? = aiProviders().firstOrNull { it.providerId == providerId }

    public fun aiInterceptors(): List<AiInterceptorModule> =
        getByType(ModuleType.AI_INTERCEPTOR)
            .filterIsInstance<AiInterceptorModule>()
            .sortedBy { it.interceptorPriority }

    public fun aiAnalyzers(): List<AiAnalyzerModule> =
        getByType(ModuleType.AI_ANALYZER)
            .filterIsInstance<AiAnalyzerModule>()
            .sortedBy { it.defaultPriority }

    public fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = aiAnalyzers().firstOrNull { it.analyzerId == analyzerId }

    public fun aiPromptProcessors(): List<AiPromptProcessorModule> =
        getByType(ModuleType.AI_PROMPT_PROCESSOR)
            .filterIsInstance<AiPromptProcessorModule>()
            .sortedBy { it.processorPriority }

    public fun aiResponseTransformers(): List<AiResponseTransformerModule> =
        getByType(ModuleType.AI_RESPONSE_TRANSFORMER)
            .filterIsInstance<AiResponseTransformerModule>()
            .sortedBy { it.transformerPriority }

    public fun aiContextEnrichers(): List<AiContextEnricherModule> =
        getByType(ModuleType.AI_CONTEXT_ENRICHER)
            .filterIsInstance<AiContextEnricherModule>()
            .sortedBy { it.enricherPriority }

    public fun aiRedactors(): List<AiRedactorModule> =
        getByType(ModuleType.AI_REDACTOR)
            .filterIsInstance<AiRedactorModule>()
            .sortedBy { it.redactorPriority }

    public fun aiTokenAllocator(): AiTokenAllocatorModule? =
        getByType(ModuleType.AI_TOKEN_ALLOCATOR)
            .filterIsInstance<AiTokenAllocatorModule>()
            .minByOrNull { it.defaultPriority }

    public fun pipelineExtensions(): List<PipelineExtension> =
        getByType(ModuleType.PIPELINE_EXTENSION)
            .filterIsInstance<PipelineExtension>()
            .sortedBy { it.defaultPriority }

    public fun enrichmentProviders(): List<EnrichmentProviderModule> =
        getByType(ModuleType.ENRICHMENT_PROVIDER)
            .filterIsInstance<EnrichmentProviderModule>()
            .sortedBy { it.defaultPriority }

    public fun aiReports(): List<AiReportModule> =
        getByType(ModuleType.AI_REPORT)
            .filterIsInstance<AiReportModule>()
            .sortedBy { it.defaultPriority }

    public fun aiMetrics(): List<AiMetricModule> =
        getByType(ModuleType.AI_METRIC)
            .filterIsInstance<AiMetricModule>()
            .sortedBy { it.defaultPriority }
}
