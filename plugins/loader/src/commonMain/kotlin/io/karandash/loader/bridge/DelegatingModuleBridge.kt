package io.karandash.loader.bridge

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.api.config.ConfigProperty
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.loader.bridge.ai.BridgeAiAnalyzerModule
import io.karandash.loader.bridge.ai.BridgeAiContextEnricherModule
import io.karandash.loader.bridge.ai.BridgeAiInterceptorModule
import io.karandash.loader.bridge.ai.BridgeAiMetricModule
import io.karandash.loader.bridge.ai.BridgeAiPromptProcessorModule
import io.karandash.loader.bridge.ai.BridgeAiProviderModule
import io.karandash.loader.bridge.ai.BridgeAiRedactorModule
import io.karandash.loader.bridge.ai.BridgeAiReportModule
import io.karandash.loader.bridge.ai.BridgeAiResponseTransformerModule
import io.karandash.loader.bridge.ai.BridgeAiTokenAllocatorModule
import io.karandash.loader.bridge.modules.BridgeEnrichedReporterModule
import io.karandash.loader.bridge.modules.BridgeEnrichmentProviderModule
import io.karandash.loader.bridge.modules.BridgeMutatorModule
import io.karandash.loader.bridge.modules.BridgePlatformServiceModule
import io.karandash.loader.bridge.modules.BridgeProfileModule
import io.karandash.loader.bridge.modules.BridgeProviderModule
import io.karandash.loader.bridge.modules.BridgeReporterModule
import io.karandash.loader.bridge.modules.BridgeRuleModule
import io.karandash.loader.bridge.modules.BridgeScriptModule
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement

/**
 * Per-module-type dispatch shared by every guest bridge (JS, WASM, JVM native, [TransportModuleBridge]): every SDK
 * primary method is forwarded to the [transport]-backed bridge module for its type ([BridgeRuleModule], …).
 *
 * Subclasses own only the transport and the metadata/lifecycle members that read it (ids, priorities,
 * `initialize`/`shutdown`), and override a forwarded method where their guest ABI needs different semantics.
 * Delegates are created on first use, so a subclass may build [transport] after metadata it reads first.
 */
@OptIn(io.karandash.api.ExperimentalKarandashApi::class)
abstract class DelegatingModuleBridge :
    RuleModule,
    ReporterModule,
    EnrichedReporterModule,
    PlatformServiceModule,
    EnrichmentProviderModule,
    ProfileModule,
    AiProviderModule,
    AiInterceptorModule,
    AiAnalyzerModule,
    AiPromptProcessorModule,
    AiResponseTransformerModule,
    AiContextEnricherModule,
    AiRedactorModule,
    AiTokenAllocatorModule,
    AiReportModule,
    AiMetricModule,
    ScriptModule {
    internal abstract val transport: BridgeTransport
    internal abstract val descriptor: BridgeDescriptor

    // Inherited with defaults from several module interfaces; each bridge answers them itself.
    abstract override val type: ModuleType
    abstract override val supportedEnrichment: List<String>
    abstract override val requiredEnrichment: List<String>

    private val ruleDelegate by lazy { BridgeRuleModule(transport) }
    private val reporterDelegate by lazy { BridgeReporterModule(transport) }
    private val enrichedReporterDelegate by lazy { BridgeEnrichedReporterModule(transport) }
    private val commentPosterDelegate by lazy { BridgePlatformServiceModule(transport) }
    private val mutatorDelegate by lazy { BridgeMutatorModule(transport) }
    private val providerDelegate by lazy { BridgeProviderModule(transport) }
    private val profileDelegate by lazy { BridgeProfileModule(transport) }
    private val aiProviderDelegate by lazy { BridgeAiProviderModule(transport) }
    private val aiInterceptorDelegate by lazy { BridgeAiInterceptorModule(transport) }
    private val aiAnalyzerDelegate by lazy { BridgeAiAnalyzerModule(transport) }
    private val aiPromptProcessorDelegate by lazy { BridgeAiPromptProcessorModule(transport) }
    private val aiTransformerDelegate by lazy { BridgeAiResponseTransformerModule(transport) }
    private val aiEnricherDelegate by lazy { BridgeAiContextEnricherModule(transport) }
    private val aiRedactorDelegate by lazy { BridgeAiRedactorModule(transport) }
    private val aiAllocatorDelegate by lazy { BridgeAiTokenAllocatorModule(transport) }
    private val scriptDelegate by lazy { BridgeScriptModule(transport) }
    private val enrichmentProviderDelegate by lazy { BridgeEnrichmentProviderModule(transport) }
    private val aiReportDelegate by lazy { BridgeAiReportModule(transport) }
    private val aiMetricDelegate by lazy { BridgeAiMetricModule(transport) }

    // --- Per-type metadata, read through the transport by the shared readers (one answer on every transport) ---

    override val profileId: String get() = profileDelegate.profileId
    override val summary: String get() = profileDelegate.summary
    override val isDefaultZeroConfigProfile: Boolean get() = profileDelegate.isDefaultZeroConfigProfile
    override val defaultSeverity: Severity get() = profileDelegate.defaultSeverity
    override val providerId: String get() = aiProviderDelegate.providerId
    override val defaultModel: String get() = aiProviderDelegate.defaultModel
    override val apiKeyEnvVar: String? get() = aiProviderDelegate.apiKeyEnvVar
    override val baseUrlEnvVar: String? get() = aiProviderDelegate.baseUrlEnvVar
    override val analyzerId: String get() = aiAnalyzerDelegate.analyzerId
    override val featureKey: String get() = aiAnalyzerDelegate.featureKey
    override val defaultPrompt: String? get() = aiAnalyzerDelegate.defaultPrompt
    override val reportId: String get() = aiReportDelegate.reportId
    override val reportName: String get() = aiReportDelegate.reportName
    override val metricId: String get() = aiMetricDelegate.metricId
    override val metricName: String get() = aiMetricDelegate.metricName
    override val unit: String get() = aiMetricDelegate.unit

    // The per-type priorities are aliases of the `defaultPriority` op unless the guest exports its own member (JS).
    override val interceptorPriority: Int get() = transport.getIntProperty("interceptorPriority", DEFAULT_TYPE_PRIORITY)
    override val processorPriority: Int get() = transport.getIntProperty("processorPriority", DEFAULT_TYPE_PRIORITY)
    override val transformerPriority: Int get() = transport.getIntProperty("transformerPriority", DEFAULT_TYPE_PRIORITY)
    override val enricherPriority: Int get() = transport.getIntProperty("enricherPriority", DEFAULT_TYPE_PRIORITY)
    override val redactorPriority: Int get() = transport.getIntProperty("redactorPriority", DEFAULT_TYPE_PRIORITY)

    // --- RuleModule ---

    override suspend fun contributedRules(): Set<String> = ruleDelegate.contributedRules()

    override suspend fun ruleExplanation(ruleId: String): String? = ruleDelegate.ruleExplanation(ruleId)

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> = ruleDelegate.validate(ruleId, mr, configElement)

    // --- ReporterModule ---

    override suspend fun defaultConfig(): JsonElement = reporterDelegate.defaultConfig()

    override suspend fun configSchema(): List<ConfigProperty> = reporterDelegate.configSchema()

    override suspend fun messages(): List<ModuleMessage> = transport.readMessages()

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement,
    ): String? = reporterDelegate.report(findings, passed, config)

    override suspend fun runtimeReporterConfig(
        reportersConfig: JsonElement,
        cliParams: JsonElement,
    ): JsonElement = reporterDelegate.runtimeReporterConfig(reportersConfig, cliParams)

    // --- EnrichedReporterModule ---

    override suspend fun reportEnriched(
        report: ValidationReport,
        config: JsonElement,
    ): String? = enrichedReporterDelegate.reportEnriched(report, config)

    // --- PlatformServiceModule: provider ---

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = providerDelegate.fetchMergeRequest(projectId, mrId)

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = providerDelegate.fetchMergeRequestByUrl(url)

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = providerDelegate.detectCiEnvironment(env)

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? = providerDelegate.parseMergeRequestUrl(url)

    override suspend fun parseGitRemoteUrl(url: String): String? = providerDelegate.parseGitRemoteUrl(url)

    override suspend fun defaultApiUrl(): String? = providerDelegate.defaultApiUrl()

    override suspend fun authTokenHint(): String? = providerDelegate.authTokenHint()

    override suspend fun aliases(): Set<String> = providerDelegate.aliases()

    // --- PlatformServiceModule: mutator ---

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = mutatorDelegate.addLabels(projectId, mrId, labels)

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = mutatorDelegate.removeLabels(projectId, mrId, labels)

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ) = mutatorDelegate.setDraft(projectId, mrId, draft)

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ) = mutatorDelegate.updateTitle(projectId, mrId, title)

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ) = mutatorDelegate.updateDescription(projectId, mrId, description)

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ) = mutatorDelegate.approve(projectId, mrId)

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ) = mutatorDelegate.unapprove(projectId, mrId)

    // --- PlatformServiceModule: comments ---

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String = commentPosterDelegate.postComment(projectId, mrId, body)

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) = commentPosterDelegate.updateComment(projectId, mrId, commentId, body)

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = commentPosterDelegate.findExistingComment(projectId, mrId, marker)

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = commentPosterDelegate.postInlineComment(projectId, mrId, body, filePath, line, diffRefs, startLine)

    override suspend fun deleteMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = commentPosterDelegate.deleteMarkedInlineComments(projectId, mrId, marker)

    override suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = commentPosterDelegate.resolveMarkedInlineComments(projectId, mrId, marker)

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = commentPosterDelegate.findAllMatchingComments(projectId, mrId, marker)

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String>? = commentPosterDelegate.listOpenMergeRequestIds(projectId, targetBranch, limit)

    override suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String>? = commentPosterDelegate.listDismissedInlineComments(projectId, mrId, marker)

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) = commentPosterDelegate.deleteComment(projectId, mrId, commentId)

    override suspend fun renderSuggestionBlock(suggestedCode: String): String = commentPosterDelegate.renderSuggestionBlock(suggestedCode)

    // --- ProfileModule ---

    override suspend fun tags(): Set<String> = profileDelegate.tags()

    override suspend fun ruleConfigs(): Map<String, JsonElement> = profileDelegate.ruleConfigs()

    override suspend fun reporterConfigs(): Map<String, JsonElement> = profileDelegate.reporterConfigs()

    override suspend fun policies(): List<JsonElement> = profileDelegate.policies()

    // --- AI modules ---

    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse = aiProviderDelegate.complete(request)

    override suspend fun intercept(
        request: AiCompletionRequest,
        context: AiInterceptContext,
        chain: AiInterceptChain,
    ): AiCompletionResponse = aiInterceptorDelegate.intercept(request, context, chain)

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = aiAnalyzerDelegate.analyze(context)

    override suspend fun processPrompt(
        prompt: String,
        context: AiPromptProcessorContext,
    ): String = aiPromptProcessorDelegate.processPrompt(prompt, context)

    override suspend fun processSystemPrompt(
        systemPrompt: String?,
        context: AiPromptProcessorContext,
    ): String? = aiPromptProcessorDelegate.processSystemPrompt(systemPrompt, context)

    override suspend fun transform(
        response: AiCompletionResponse,
        request: AiCompletionRequest,
        context: AiResponseTransformerContext,
    ): AiCompletionResponse = aiTransformerDelegate.transform(response, request, context)

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> = aiEnricherDelegate.enrich(mr, context)

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? = aiEnricherDelegate.provideBlock(mr, changes, config, budget)

    override suspend fun redact(
        text: String,
        context: AiRedactorContext,
    ): String = aiRedactorDelegate.redact(text, context)

    override suspend fun allocate(
        rules: Set<String>,
        totalBudget: Int,
        priorities: Map<String, Int>,
    ): Map<String, Int> = aiAllocatorDelegate.allocate(rules, totalBudget, priorities)

    // Both AiReportModule and AiMetricModule define requiredInputs with the same default;
    // override here to resolve the ambiguity at the bridge level. This is only the fallback for a
    // guest whose descriptor omits `requiredInputs`: AiReportProxy/AiMetricProxy read the declared
    // inputs from the descriptor (ModuleDescriptor.REQUIRED_INPUTS) for JS, WASM, native and JAR guests.
    override val requiredInputs: Set<ReportInput>
        get() = setOf(ReportInput.FINDINGS)

    override suspend fun generate(context: ReportContext): ReportResult = aiReportDelegate.generate(context)

    override suspend fun compute(context: ReportContext): MetricResult = aiMetricDelegate.compute(context)

    // --- ScriptModule ---

    override suspend fun execute(
        scripts: List<JsonElement>,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding> = scriptDelegate.execute(scripts, mr, context)

    // --- EnrichmentProviderModule ---

    override val providedEnrichment: Set<String> get() = enrichmentProviderDelegate.providedEnrichment

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> = enrichmentProviderDelegate.enrich(mr, config, upstreamContext)

    private companion object {
        const val DEFAULT_TYPE_PRIORITY = 100
    }
}
