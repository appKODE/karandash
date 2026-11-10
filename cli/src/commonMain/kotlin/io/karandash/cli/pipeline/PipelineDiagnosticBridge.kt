package io.karandash.cli.pipeline

import io.karandash.Version
import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunSession
import io.karandash.cache.CacheManager
import io.karandash.cli.commands.validate.ConfiguredFormatOutput
import io.karandash.config.DiagnosticConfig
import io.karandash.diagnostics.AiHealthDiagnostic
import io.karandash.diagnostics.DiagnosticCollector
import io.karandash.diagnostics.FeedbackDiagnostic
import io.karandash.diagnostics.FindingSummaryDiagnostic
import io.karandash.diagnostics.PipelineContext
import io.karandash.diagnostics.PlatformDiagnostics
import io.karandash.engine.ValidationResult
import io.karandash.http.HttpDiagnosticListener
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.finding.errorsCount
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import io.karandash.security.TextRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bridges the [ValidationPipeline] with the [DiagnosticCollector].
 *
 * Encapsulates all diagnostic lifecycle logic so that [ValidationPipeline]
 * only needs a thin reference to this bridge. Provides its [HttpDiagnosticListener] to the run as
 * [ProviderHttpClientFactory.DIAGNOSTIC_LISTENER], so this run's HTTP requests (and no other run's) are tracked,
 * and takes the client create/close counts from the run's [ProviderHttpClientFactory.CLIENT_LEDGER].
 *
 * @lifecycle per-run
 */
@OptIn(ExperimentalKarandashApi::class)
internal class PipelineDiagnosticBridge private constructor(
    private val collector: DiagnosticCollector,
    private val outputPath: String,
    private val scope: CoroutineScope,
    private val services: RunScopedServices,
) {
    suspend fun stageStart(stage: String) {
        collector.recordStageStart(stage)
    }

    suspend fun stageEnd(
        stage: String,
        durationMs: Long,
    ) {
        collector.recordStageEnd(stage, durationMs)
    }

    suspend fun http(
        method: String,
        url: String,
        statusCode: Int?,
        durationMs: Long,
        responseSizeBytes: Long? = null,
        error: String? = null,
    ) {
        collector.recordHttp(method, url, statusCode, durationMs, responseSizeBytes, error)
    }

    /**
     * Records rule timings and the finding summary. Rules in [cachedRuleIds] were served whole by an extension's
     * incremental cache and did not run, so they are recorded as cache hits; [cacheManager]'s state cache names the
     * layer that served it (`cache.source`).
     */
    suspend fun recordValidation(
        stage: ValidationStage,
        validationDurationMs: Long,
        cachedRuleIds: Set<String> = emptySet(),
        cacheManager: CacheManager? = null,
        configuredRules: Collection<String> = emptyList(),
    ) {
        collector.recordStageEnd("validation", validationDurationMs)
        val findings = stage.result.findings
        val ranRuleIds = stage.result.ruleExecutionResults.mapTo(mutableSetOf()) { it.ruleId }
        (cachedRuleIds - ranRuleIds).forEach { ruleId ->
            val ruleFindings = findings.filter { it.rule == ruleId }
            collector.recordRuleResult(
                ruleId = ruleId,
                durationMs = 0,
                fromCache = true,
                findingCount = ruleFindings.size,
                errorCount = ruleFindings.errorsCount(),
                warningCount = ruleFindings.count { it.severity == Severity.WARNING },
                infoCount = ruleFindings.count { it.severity == Severity.INFO },
            )
        }
        cacheManager?.getCasCache(CacheManager.STATE_NAMESPACE)?.let { state ->
            collector.recordCacheSource(state.indexSource().id, firstRun = state.foundNothing())
        }
        stage.result.ruleExecutionResults.forEach { rr ->
            val ruleFindings = findings.filter { it.rule == rr.ruleId }
            collector.recordRuleResult(
                ruleId = rr.ruleId,
                durationMs = rr.executionDurationNs / NS_PER_MS,
                fromCache = rr.fromCache,
                findingCount = ruleFindings.size,
                startMs = rr.startOffsetNs / NS_PER_MS,
                endMs = rr.endOffsetNs / NS_PER_MS,
                errorCount = ruleFindings.errorsCount(),
                warningCount = ruleFindings.count { it.severity == Severity.WARNING },
                infoCount = ruleFindings.count { it.severity == Severity.INFO },
            )
        }
        val allFindings = stage.result.findings
        recordFindingSummary(allFindings, stage.result.passed)
        collector.recordOutcomes(
            DiagnosticOutcomes.rules(configuredRules, stage.result),
            DiagnosticOutcomes.findings(allFindings),
        )
    }

    /**
     * `summary.finding_summary` for [findings]. [recordValidation] records the engine's findings; the pipeline records
     * again with the findings it reports (scripts, extensions, suppression and cached findings applied), which replaces them.
     */
    suspend fun recordFindingSummary(
        findings: List<Finding>,
        passed: Boolean,
    ) {
        collector.recordFindingSummary(
            FindingSummaryDiagnostic(
                total = findings.size,
                errors = findings.errorsCount(),
                warnings = findings.count { it.severity == Severity.WARNING },
                infos = findings.count { it.severity == Severity.INFO },
                passed = passed,
            ),
        )
    }

    /** `summary.ai.health` from the AI rule's provenance; nothing when AI was not configured. */
    suspend fun recordAiHealth(provenance: ReviewProvenance?) {
        val status = provenance?.aiStatus ?: return
        val called = provenance.analyzers.filter { it.status != ProvenanceStatus.SKIPPED }
        collector.recordAiHealth(
            AiHealthDiagnostic(
                calls = called.size,
                failed = called.count { it.status == ProvenanceStatus.FAILED },
                reason = status,
                context = provenance.analyzers.mapNotNull { a -> a.context?.let { a.ruleId to it } }.toMap(),
            ),
            funnel = provenance.funnel,
        )
    }

    suspend fun recordContext(context: PipelineContext) {
        collector.recordContext(context)
    }

    suspend fun recordPipelineContext(
        ciContext: CIContext,
        ruleIds: Set<String>,
        configStage: ConfigLoadStage,
        mr: MergeRequest,
        result: ValidationResult? = null,
    ) {
        val changedLines = mr.changes.sumOf { it.additions + it.deletions }
        val allRuleIds = DiagnosticOutcomes.ruleIds(ruleIds, result)
        val chain = configStage.configLayers.map { TextRedactor.redactForLog(it.first) }
        collector.recordContext(
            PipelineContext(
                platform = ciContext.platform,
                buildVariant = PlatformDiagnostics.buildVariant,
                os = PlatformDiagnostics.osName,
                karandashVersion = Version.VERSION,
                configSource = configStage.configFilePath ?: "zero-config",
                ruleCount = allRuleIds.size,
                ruleIds = allRuleIds,
                mrUrl = mrUrl(mr.iid),
                mrChangedLines = changedLines,
                mrFileCount = mr.changes.size,
                platformConfidence = ciContext.platformConfidence,
                platformSource = ciContext.platformSource,
                mrIid = mr.iid.takeIf { it > 0 },
                headSha = mr.diffRefs?.headSha ?: PlatformEnvironment.getEnv("CI_COMMIT_SHA"),
                configChain = if (chain.size > 1) chain else emptyList(),
            ),
        )
    }

    /** The MR page; in GitLab CI outside a merge request pipeline, built from the project URL. */
    private fun mrUrl(iid: Int): String? {
        val env = { name: String -> PlatformEnvironment.getEnv(name) }
        return mrView(iid, "", env).url
            ?: env("CI_PROJECT_URL")?.takeIf { env("GITLAB_CI") == "true" && iid > 0 }?.let { "$it/-/merge_requests/$iid" }
    }

    /**
     * Reads reviewer feedback on this MR's karandash findings once ([PlatformServiceModule.fetchFindingFeedback]) into
     * the report's `feedback` block. Counted only; a failed read is logged and leaves the block out.
     */
    suspend fun recordFeedback(
        provider: ProviderStage,
        logger: Logger,
    ) {
        val platform = provider.provider ?: return
        val project = provider.projectId ?: return
        val mrId = provider.mrId ?: return
        platform
            .fetchFindingFeedback(project, mrId)
            .onSuccess { collector.recordFeedback(FeedbackDiagnostic.of(it)) }
            .onFailure { logger.warn("Could not read finding feedback: ${it.message}") }
    }

    suspend fun recordEnrichmentResult(
        providerId: String,
        durationMs: Long,
        fromCache: Boolean,
        outputIds: List<String>,
    ) {
        collector.recordEnrichmentResult(providerId, durationMs, fromCache, outputIds)
    }

    suspend fun recordReporterResult(
        pair: ConfiguredFormatOutput,
        formatMs: Long,
        outputMs: Long,
    ) {
        collector.recordReporterResult(pair.formatId, pair.outputId, formatMs, outputMs)
    }

    fun recordClientCreate() {
        collector.recordClientCreate()
    }

    suspend fun finalize(
        totalMs: Long,
        logger: Logger,
    ) {
        services[ProviderHttpClientFactory.CLIENT_LEDGER]?.let { collector.recordClientLifecycle(it.createdCount, it.closedCount) }
        collector.stopAndFinalize(totalMs)
        scope.cancel()
        logger.info("Diagnostics written to $outputPath")
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L

        suspend fun createIfConfigured(
            session: RunSession,
            cliDiagnosticPath: String?,
            diagConfig: DiagnosticConfig?,
            configLoadMs: Long,
            logger: Logger,
        ): PipelineDiagnosticBridge? {
            val path = cliDiagnosticPath ?: diagConfig?.output ?: return null
            val sampleMs = diagConfig?.interval?.millis ?: DiagnosticCollector.DEFAULT_SAMPLE_INTERVAL_MS
            val flushMs = diagConfig?.flushInterval?.millis ?: DiagnosticCollector.DEFAULT_FLUSH_INTERVAL_MS
            // Owned scope, cancelled by finalize(): no sampler, flusher or HTTP record outlives the run.
            val scope = CoroutineScope(SupervisorJob())
            val collector = DiagnosticCollector(path, sampleMs, flushMs, logger)
            collector.start(scope)
            collector.recordStageEnd("configLoad", configLoadMs)
            // The run's HTTP diagnostic listener: every request this run makes through ProviderHttpClientFactory is
            // tracked. HttpDiagnosticListener.onRequestComplete is non-suspend, so we fire-and-forget into the scope.
            // bridge-boundary: non-suspend listener interface calling suspend collector.recordHttp
            val listener = HttpDiagnosticListener { method, url, statusCode, durationMs, responseSizeBytes, error ->
                scope.launch {
                    collector.recordHttp(method, url, statusCode, durationMs, responseSizeBytes, error)
                }
            }
            session.provide(ProviderHttpClientFactory.DIAGNOSTIC_LISTENER, listener)

            return PipelineDiagnosticBridge(collector, path, scope, session.services)
        }
    }
}
