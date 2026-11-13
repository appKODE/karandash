@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.cache.KarandashCache
import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.SkipReasonProvider
import io.karandash.api.platform.nullIfUnsupported
import io.karandash.api.run.DismissedInlineComments
import io.karandash.api.run.MergeRequestSearch
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.MergeRequestValidator
import io.karandash.api.run.OpenMergeRequestLister
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.run.RunSession
import io.karandash.api.run.dumpJson
import io.karandash.config.DryRunScope
import io.karandash.engine.ValidationEngine
import io.karandash.helpers.RuleIds
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import io.karandash.utils.DurationFormatter
import kotlinx.serialization.Serializable
import kotlin.time.measureTimedValue

/**
 * Stages 4-7 of [ValidationPipeline] on a [PreparedRun]: validation (with the incremental-cache short-circuit),
 * scripts and Dangerfile, extension post-processing, reporting, auto-approve, auto-fix and the final result.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class PipelineExecutionStage(
    private val options: ValidationOptions,
    private val moduleInitializer: ModuleInitializer,
    private val scriptExecutor: ScriptExecutor,
    private val fixAndResult: FixAndResultHandler,
    private val reportGenerator: ReportGenerator,
    private val session: RunSession,
    private val resources: PipelineRunResources,
) {
    private val logger: Logger = options.logger
    private var baselineComparison: BaselineComparison? = null

    suspend fun execute(run: PreparedRun): ValidationPipelineResult {
        val validationStage = validate(run)
        val augmentedValidationStage = runScripts(run, validationStage)
        val postMetadata = afterValidation(run, augmentedValidationStage)
        return reportAndFinish(run, postMetadata)
    }

    private suspend fun validate(run: PreparedRun): ValidationStage {
        val configStage = run.configStage
        val extensionCachedFindings = findCachedFindings(run.mr, run.extensions, configStage, logger, options)

        // Full cache hit: rules owned by the caching extension (e.g. AI) reuse cached findings;
        // every other rule still runs.
        val cachedRuleIds = if (extensionCachedFindings != null) extensionOwnedRuleIds(configStage) else emptySet()
        val diag = resources.diag
        diag?.stageStart("validation")
        val (validationStage, validationDuration) = measureTimedValue {
            if (extensionCachedFindings != null) {
                logger.info(
                    "IncrementalCache: all files unchanged, using ${extensionCachedFindings.size} cached finding(s) " +
                        "for ${cachedRuleIds.size} cached rule(s); other rules run",
                )
            }
            val (vs, vDuration) = measureTimedValue {
                runValidation(run, run.gating.enabledModules + cachedRuleIds.associateWith { false })
            }
            logger.debug(
                "Validation: ${DurationFormatter.formatMs(vDuration.inWholeMilliseconds)} (${vs.result.findings.size} findings)"
            )
            logRuleTimings(vs, logger)
            withCachedFindings(vs, extensionCachedFindings)
        }
        val configuredRules = configStage.config.rules.ruleConfigs.keys
        diag?.recordValidation(
            validationStage,
            validationDuration.inWholeMilliseconds,
            cachedRuleIds,
            resources.cacheManager,
            configuredRules,
        )
        diag?.recordPipelineContext(run.gating.ciContext, configuredRules, configStage, run.mr, validationStage.result)
        diag?.recordFeedback(run.providerStage, logger)
        return validationStage
    }

    private suspend fun runValidation(
        run: PreparedRun,
        enabledModules: Map<String, Boolean>
    ): ValidationStage {
        val engine = createEngine(
            run,
            enabledModules,
            PipelineInfrastructure.ruleCache(resources.cacheManager, options.noCache),
            run.providerStage.provider,
            PipelineInfrastructure.parseInvalidatedRules(options.invalidateRules),
        )
        val skipExtensionRules = extensionOwnedRuleIds(run.configStage).associateWith { false }
        session.provide(
            RunServiceKeys.RULE_VALIDATOR,
            MergeRequestValidator { mr -> createEngine(run, enabledModules + skipExtensionRules, null, null).validate(mr).findings },
        )
        provideOpenMergeRequests(run)
        provideDismissedInlineComments(run)
        provideReviewThreads(run)
        provideRepositoryFile(run)
        provideMergeRequestSearch(run)
        run.providerStage.provider?.let { session.provide(RunServiceKeys.UNAVAILABLE_FIELDS, it.unavailableMergeRequestFields()) }
        val result = engine.validate(withApprovalSignals(run))
        dumpJson("validation-result.json") { result }
        return ValidationStage(engine = engine, result = result)
    }

    private suspend fun withApprovalSignals(run: PreparedRun): MergeRequest {
        val provider = run.providerStage.provider
        val project = run.providerStage.projectId
        val mrId = run.providerStage.mrId
        val fetch = if (provider != null && project != null &&
            mrId != null
        ) {
            suspend { provider.fetchApprovalSignals(project, mrId) }
        } else {
            null
        }
        return ApprovalSignalStage(logger).apply(run.mr, run.configStage.config.rules.ruleConfigs[RuleIds.APPROVAL], fetch)
    }

    private fun provideOpenMergeRequests(run: PreparedRun) {
        val provider = run.providerStage.provider ?: return
        val project = run.providerStage.projectId ?: return
        // limit: one full fetch per sibling; add a changes-only platform call if API budget matters
        session.provide(
            RunServiceKeys.OPEN_MERGE_REQUESTS,
            OpenMergeRequestLister { branch, limit ->
                nullIfUnsupported { provider.listOpenMergeRequestIds(project, branch, limit) }?.map {
                    provider.fetchMergeRequest(project, it, run.fetchOptions)
                }
            },
        )
    }

    private fun provideDismissedInlineComments(run: PreparedRun) {
        val provider = run.providerStage.provider ?: return
        val project = run.providerStage.projectId ?: return
        val mrId = run.providerStage.mrId ?: return
        session.provide(
            RunServiceKeys.DISMISSED_INLINE_COMMENTS,
            DismissedInlineComments {
                nullIfUnsupported { provider.listDismissedInlineComments(project, mrId, InlineFindingMarker.PREFIX) }
            },
        )
    }

    private suspend fun provideRepositoryFile(run: PreparedRun) {
        val provider = run.providerStage.provider ?: return
        val project = run.providerStage.projectId ?: return
        if (!provider.supportsFileContent) return
        session.provide(
            RunServiceKeys.REPOSITORY_FILE,
            MemoizedRepositoryFileReader(localRepository(run.mr, logger) { ref, path -> provider.fetchFileContent(project, ref, path) }),
        )
    }

    private fun provideMergeRequestSearch(run: PreparedRun) {
        val provider = run.providerStage.provider ?: return
        val project = run.providerStage.projectId ?: return
        session.provide(
            RunServiceKeys.MERGE_REQUEST_SEARCH,
            MergeRequestSearch { text, author, limit ->
                nullIfUnsupported { provider.searchMergeRequests(project, text, author, limit) }
            },
        )
    }

    private fun provideReviewThreads(run: PreparedRun) {
        val provider = run.providerStage.provider ?: return
        val project = run.providerStage.projectId ?: return
        val mrId = run.providerStage.mrId ?: return
        val threads = MergeRequestThreads.of(provider, project, mrId)
        // --dry-run-comment posts nothing to the MR, thread replies and resolves included
        session.provide(RunServiceKeys.REVIEW_THREADS, if (options.dryRunComment) ReadOnlyMergeRequestThreads(threads, logger) else threads)
    }

    private fun createEngine(
        run: PreparedRun,
        enabledModules: Map<String, Boolean>,
        ruleCache: KarandashCache?,
        platform: PlatformServiceModule?,
        invalidatedRules: Set<String> = emptySet(),
    ): ValidationEngine {
        val ciContext = run.gating.ciContext
        return run.scope.createEngine(
            enabledModules = enabledModules,
            parsedPolicies = run.configStage.config.policies ?: emptyList(),
            envLookup = KarandashEnvSecurity.withoutSecrets(PlatformEnvironment::getEnv),
            ciPlatform = ciContext.platform,
            isCI = ciContext.isCI,
            patternExpander = { pattern -> run.patternResolver.resolveNested(pattern) },
            enrichmentContext = run.gating.enrichmentContext,
            run = session,
            ruleCache = ruleCache,
            platform = platform,
            invalidatedRules = invalidatedRules,
        )
    }

    /** Runs configured scripts and the Dangerfile unless a policy switched them off. */
    private suspend fun runScripts(
        run: PreparedRun,
        validationStage: ValidationStage
    ): ValidationStage {
        val policyResult = run.gating.policyResult
        val scriptAugmented = if (policyResult.scriptsOverride == false) {
            logger.debug("Scripts: skipped by policy")
            validationStage
        } else {
            val (result, scriptDuration) = measureTimedValue {
                scriptExecutor.runScriptsIfConfigured(run.configStage, run.mr, validationStage)
            }
            if (result !== validationStage) {
                logger.debug("Scripts: ${DurationFormatter.formatMs(scriptDuration.inWholeMilliseconds)}")
            }
            result
        }

        return if (policyResult.dangerfileOverride == false) {
            logger.debug("Dangerfile: skipped by policy")
            scriptAugmented
        } else {
            val (result, dangerDuration) = measureTimedValue {
                scriptExecutor.runDangerfileIfConfigured(
                    run.configStage,
                    run.mr,
                    scriptAugmented,
                    options.dangerfilePathOverride,
                    options.configPathStr,
                )
            }
            if (result !== scriptAugmented) {
                logger.debug("Dangerfile: ${DurationFormatter.formatMs(dangerDuration.inWholeMilliseconds)}")
            }
            result
        }
    }

    /** Passes the findings through every extension's `afterValidation` hook. */
    private suspend fun afterValidation(
        run: PreparedRun,
        augmentedValidationStage: ValidationStage
    ): ValidationStage {
        val (postProcessedFindings, afterValDuration) = measureTimedValue {
            var findings = augmentedValidationStage.result.findings
            for (ext in run.extensions) {
                val input = findings
                findings = runExtensionHook(ext, "afterValidation", logger, input) {
                    ext.afterValidation(input, run.mr, run.configStage.extensionConfig)
                }
                dumpJson("after-validation/${ext.id}.json") { findings }
            }
            findings
        }
        if (run.extensions.isNotEmpty()) {
            logger.debug("Enrichment (after): ${DurationFormatter.formatMs(afterValDuration.inWholeMilliseconds)}")
        }
        val suppression = FindingSuppression.apply(
            postProcessedFindings,
            run.mr.description,
            options.baselinePath ?: run.configStage.config.baseline,
            options.updateBaseline,
        )
        baselineComparison = suppression.comparison
        dumpJson("suppressed.json") { suppression.findings }
        if (suppression.disabledRules.isNotEmpty()) logger.info("Disabled by MR description: ${suppression.disabledRules.joinToString()}")
        return if (suppression.findings !== augmentedValidationStage.result.findings) {
            ValidationStage(
                engine = augmentedValidationStage.engine,
                result = augmentedValidationStage.result.copy(findings = suppression.findings),
            )
        } else {
            augmentedValidationStage
        }
    }

    private suspend fun reportAndFinish(
        run: PreparedRun,
        postMetadata: ValidationStage
    ): ValidationPipelineResult {
        val extensions = run.extensions
        for (ext in extensions) {
            runExtensionHook(ext, "saveIncrementalState", logger, Unit) {
                ext.saveIncrementalState(run.mr, postMetadata.result.findings)
            }
        }

        // Merge cached findings for reporting and compute changedFiles for scoped inline posting.
        // This MUST happen AFTER saveIncrementalState (cache save is independent) and
        // BEFORE beforeReporting (which appends digest/report findings).
        val changedFiles = extensions.firstNotNullOfOrNull { ext ->
            runExtensionHook(ext, "incrementalChangedFiles", logger, null) { ext.incrementalChangedFiles() }
        }
        // Cached findings pass the same suppression (baseline, `karandash-disable`) as the fresh ones did.
        val cachedForReport =
            FindingSuppression
                .apply(
                    cachedFindingsToMerge(postMetadata.result.findings, extensions, logger),
                    run.mr.description,
                    options.baselinePath ?: run.configStage.config.baseline,
                    updateBaseline = false,
                ).findings
        val reportFindings = beforeReporting(run, postMetadata.result.findings + cachedForReport)
        // T: re-read provenance now that afterValidation recorded its filter counts and beforeReporting its hash chain.
        val provenance = run.configStage.registry
            .ruleModules()
            .filterIsInstance<SkipReasonProvider>()
            .firstNotNullOfOrNull { it.reviewProvenance() }
            ?.let { if (it.enrichers.isEmpty()) it.copy(enrichers = run.gating.enrichers) else it }
            ?: postMetadata.result.provenance
        val reportMetadata = postMetadata.copy(
            result = postMetadata.result.copy(findings = reportFindings, provenance = provenance),
        )
        dumpJson("report-findings.json") { reportFindings }

        val diag = resources.diag
        diag?.recordFindingSummary(reportFindings, reportMetadata.result.passed)
        diag?.recordAiHealth(provenance)
        diag?.stageStart("reporting")
        val (reportingStage, reportDuration) = measureTimedValue {
            reportGenerator.generateReports(
                run.configStage,
                run.scope,
                reportMetadata,
                run.mr,
                providerConfigKey = run.providerStage.provider?.configKey,
                moduleEnabledOverrides = run.gating.policyResult.moduleEnabledOverrides,
                timingData = run.gating.timingData,
                diag = diag,
                changedFiles = changedFiles,
                session = session,
                baselineComparison = baselineComparison,
            )
        }
        logger.debug("Reporting: ${DurationFormatter.formatMs(reportDuration.inWholeMilliseconds)}")
        diag?.stageEnd("reporting", reportDuration.inWholeMilliseconds)

        // With --gate-on-cached, cached findings shown in the report also count for auto-approve and exit code.
        val gatedCached = if (options.gateOnCached) {
            reportFindings.toSet().let { shown -> cachedForReport.filter { it in shown } }
        } else {
            emptyList()
        }
        applyAutoApprove(run, postMetadata.withExtraFindings(gatedCached))
        val fixStage = applyFixes(run, postMetadata, reportingStage)
        val result = fixAndResult.computeResult(run.configStage, run.mr, fixStage, reportingStage, gatedCached)
        CiLogSummary.log(reportFindings, result.exitCode, reportingStage.pairs, logger)
        dumpJson("result.json") { RunResultDump(result.exitCode, reportMetadata.result.passed, reportFindings.size) }
        return result
    }

    /** Passes the report findings through every extension's `beforeReporting` hook. */
    private suspend fun beforeReporting(
        run: PreparedRun,
        findings: List<Finding>
    ): List<Finding> {
        var reportFindings = findings
        val beforeReportingCount = reportFindings.size
        for (ext in run.extensions) {
            val input = reportFindings
            reportFindings = runExtensionHook(ext, "beforeReporting", logger, input) { ext.beforeReporting(input, run.mr) }
            dumpJson("before-reporting/${ext.id}.json") { reportFindings }
        }
        if (reportFindings.size != beforeReportingCount) {
            logger.debug(
                "beforeReporting: findings changed from $beforeReportingCount to ${reportFindings.size}",
            )
        }
        return reportFindings
    }

    private suspend fun applyAutoApprove(
        run: PreparedRun,
        gateMetadata: ValidationStage
    ) {
        if (run.gating.policyResult.autoApproveOverride == false) {
            logger.debug("Auto-approve: skipped by policy")
            return
        }
        AutoApproveHandler(
            logger = logger,
            autoApproveFlag = options.autoApprove,
            dryRun = options.dryRun ||
                run.configStage.config.activeDryRunScope == DryRunScope.ALL,
            runtimeModuleInitializer = moduleInitializer::initializeRuntimeModule,
            resolveCIContext = { s -> fixAndResult.resolveCIContext(s.registry.platformServices()) },
            resolveModuleConfig = { cs, m, pk ->
                fixAndResult.resolveModuleConfig(cs.config, cs.config.platforms, m, pk)
            },
        ).applyAutoApprove(run.configStage, run.scope, run.providerStage, gateMetadata)
    }

    private suspend fun applyFixes(
        run: PreparedRun,
        postMetadata: ValidationStage,
        reportingStage: ReportingStage,
    ): FixStage {
        if (run.gating.policyResult.autoFixOverride == false) {
            logger.debug("Auto-fix: skipped by policy")
            return FixStage(postMetadata.result)
        }
        val (result, fixDuration) = measureTimedValue {
            fixAndResult.applyFixes(
                run.configStage,
                run.scope,
                run.providerStage,
                postMetadata,
                reportingStage,
                run.fetchOptions,
            )
        }
        if (options.fix) {
            logger.debug("Auto-fix: ${DurationFormatter.formatMs(fixDuration.inWholeMilliseconds)}")
        }
        return result
    }
}

private fun logRuleTimings(
    stage: ValidationStage,
    logger: Logger
) {
    for (ruleResult in stage.result.ruleExecutionResults) {
        val us = ruleResult.executionDurationNs / 1_000
        val cached = if (ruleResult.fromCache) " (cached)" else ""
        val msStr = if (us < 1_000) {
            val whole = us / 1_000
            val frac = (us % 1_000) / 10
            "$whole.${frac.toString().padStart(2, '0')}ms"
        } else {
            "${us / 1_000}ms"
        }
        logger.debug("  Rule ${ruleResult.ruleId}: $msStr$cached")
    }
}

/**
 * Composite deduplication key for findings.
 *
 * Uses rule + file path + line + message hash to avoid null-fingerprint
 * collisions where `null == null` would incorrectly filter out fresh findings.
 */
private fun findingDeduplicationKey(f: Finding): String {
    val loc = f.location
    val path = (loc as? Location.FileRef)?.path ?: ""
    val line = (loc as? Location.FileRef)?.line?.toString() ?: ""
    return "${f.rule}|$path|$line|${f.message.hashCode()}"
}

/**
 * Cached findings from incremental extensions to add to the reporter finding list.
 *
 * On a partial cache hit, cached findings for unchanged files are added to the
 * fresh findings so the summary comment shows the complete picture. Deduplication
 * uses a composite key (rule + file path + line + message hash) to avoid counting
 * the same finding twice.
 */
private suspend fun cachedFindingsToMerge(
    freshFindings: List<Finding>,
    extensions: List<PipelineExtension>,
    logger: Logger,
): List<Finding> {
    val cached = extensions.firstNotNullOfOrNull { ext ->
        // An empty list from one extension (e.g. AI on a cache miss) must not hide another extension's findings.
        runExtensionHook(ext, "incrementalCachedFindings", logger, null) { ext.incrementalCachedFindings() }
            ?.takeIf { it.isNotEmpty() }
    }
    if (cached.isNullOrEmpty()) return emptyList()
    val freshKeys = freshFindings.map { findingDeduplicationKey(it) }.toSet()
    val deduped = cached.filter { findingDeduplicationKey(it) !in freshKeys }
    if (deduped.isNotEmpty()) logger.debug("Cache merge: ${deduped.size} cached finding(s) added to ${freshFindings.size} fresh")
    return deduped
}

/** Rule IDs an extension's incremental cache serves: rules of modules with [RuleModule.usesExtensionComputedConfig] (AI). */
private fun extensionOwnedRuleIds(configStage: ConfigLoadStage): Set<String> =
    configStage.config.rules.ruleConfigs.keys
        .filter { configStage.registry.findRuleHandler(it)?.usesExtensionComputedConfig == true }
        .toSet()

/** Adds full-cache-hit [cached] findings of every rule that did not run or skip this time (the extension-owned ones). */
private fun withCachedFindings(
    stage: ValidationStage,
    cached: List<Finding>?,
): ValidationStage {
    if (cached == null) return stage
    val ran = stage.result.ruleExecutionResults
        .map { it.ruleId }
        .toSet() + stage.result.skippedRules.map { it.ruleId }
    return stage.withExtraFindings(cached.filter { it.rule !in ran })
}

private fun ValidationStage.withExtraFindings(extra: List<Finding>): ValidationStage =
    if (extra.isEmpty()) this else copy(result = result.copy(findings = result.findings + extra))

/** `--dump-dir` `result.json`: how the run ended. */
@Serializable
internal data class RunResultDump(
    val exitCode: Int,
    val passed: Boolean,
    val findings: Int,
)
