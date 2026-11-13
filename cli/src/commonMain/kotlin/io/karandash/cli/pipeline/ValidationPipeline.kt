@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.EnricherProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.PipelineExtension
import io.karandash.api.run.RunSession
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.TimeoutConfig
import io.karandash.config.pipeline.isModuleDisabled
import io.karandash.enrichment.EnrichmentEngine
import io.karandash.enrichment.EnrichmentTiming
import io.karandash.enrichment.RegisteredEnrichmentProvider
import io.karandash.http.HttpClientLedger
import io.karandash.http.NetworkPolicy
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.sdk.Logger
import io.karandash.utils.DurationFormatter
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.time.measureTimedValue

/**
 * Seven-stage validation pipeline with suspend-first API.
 *
 * Stages:
 * 1. Config Loading - Load and parse config with plugin loading
 * 2. Scope Creation - Create validation scope with registry
 * 3. Provider Resolution - Resolve provider and fetch MR data
 * 4. Validation - Run validation engine
 * 5. Reporting - Select and run reporters
 * 6. Auto-fix - Apply automatic fixes (optional)
 * 7. Result - Compute exit code and final result
 *
 * This pipeline reduces ValidateCommandBase from 407 LOC to <50 LOC by
 * extracting all business logic into explicit, testable stages.
 *
 * @param options Validation options from CLI
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class ValidationPipeline(
    private val options: ValidationOptions,
    private val sharedBootstrap: PluginBootstrap,
) {
    private val logger: Logger = options.logger
    private val moduleInitializer = ModuleInitializer(logger = logger, options = options)
    private val configLoader = ConfigLoader(options = options, sharedBootstrap = sharedBootstrap, logger = logger)
    private val scriptExecutor = ScriptExecutor(
        logger = logger,
        runtimeModuleInitializer = moduleInitializer::initializeRuntimeModule,
    )
    private val fixAndResult = FixAndResultHandler(options = options, logger = logger, moduleInitializer = moduleInitializer)
    private val reportGenerator = ReportGenerator(
        logger = logger,
        options = options,
        runtimeModuleInitializer = moduleInitializer::initializeRuntimeModule,
        resolveModuleConfig = { cs, modMap, mod, pk -> fixAndResult.resolveModuleConfig(cs.config, modMap, mod, pk) },
        resolveCIContext = { s -> fixAndResult.resolveCIContext(s.registry.platformServices()) },
    )

    /** Created/closed count of every HTTP client this run builds; equal once [execute] returns. */
    internal val clientLedger = HttpClientLedger()

    /**
     * Execute all seven pipeline stages and return final result.
     *
     * Resource cleanup (provider, scope, bootstrap) is handled internally.
     * The caller only needs to inspect the returned [ValidationPipelineResult].
     * Each call is one run: its modules, AI providers and HTTP clients are torn down on return.
     */
    suspend fun execute(): ValidationPipelineResult {
        val session = sharedBootstrap.registry().forRun(logger)
        session.provide(ProviderHttpClientFactory.CLIENT_LEDGER, clientLedger)
        ProviderHttpClientFactory.installLedger(clientLedger)
        return try {
            // a fresh policy per run: its config sets `allow-private-network`, which no other run sees
            withContext(session.services + NetworkPolicy()) { executeRun(session) }
        } finally {
            ProviderHttpClientFactory.clearLedger(clientLedger)
        }
    }

    /**
     * One run: [PipelinePreparationStage] (config, infrastructure, modules, fetch, enrichment, gating), then
     * [PipelineExecutionStage] (validation, scripts, post-processing, reporting, auto-approve, auto-fix, result).
     * Resources acquired by either stage are released by [PipelineRunResources.release], also on early return.
     */
    private suspend fun executeRun(session: RunSession): ValidationPipelineResult {
        val resources = PipelineRunResources(session, logger)
        val (pipelineResult, totalDuration) = measureTimedValue {
            try {
                val preparation = PipelinePreparationStage(options, configLoader, moduleInitializer, fixAndResult, session, resources)
                val configStage = preparation.setUp()
                earlyExitResult(configStage)?.let { return it }
                val run = preparation.prepare(configStage)
                PipelineExecutionStage(options, moduleInitializer, scriptExecutor, fixAndResult, reportGenerator, session, resources)
                    .execute(run)
            } finally {
                resources.release()
            }
        }
        logger.debug("Total pipeline: ${DurationFormatter.formatMs(totalDuration.inWholeMilliseconds)}")
        resources.diag?.finalize(totalDuration.inWholeMilliseconds, logger)
        return pipelineResult
    }

    /** `--dry-run` and `--show-execution-plan` stop after config loading with a success result. */
    private fun earlyExitResult(configStage: ConfigLoadStage): ValidationPipelineResult? {
        when {
            options.dryRun && !options.showPrompt -> logger.info("Configuration is valid")
            options.showExecutionPlan -> fixAndResult.printExecutionPlan(configStage.config)
            else -> return null
        }
        return ValidationPipelineResult(
            exitCode = ExitCodes.SUCCESS,
            config = configStage.config,
            configFilePath = configStage.configFilePath,
            usingZeroConfig = configStage.usingZeroConfig,
            mr = null,
            validationResult = null,
            reportOutput = null
        )
    }

    internal fun printExecutionPlan(config: io.karandash.config.Config) = fixAndResult.printExecutionPlan(config)
}

/**
 * Checks whether any extension can serve fully cached findings for this MR.
 * Returns the cached findings list, or null if validation must proceed.
 *
 * `--invalidate-rules` is passed to every extension ([io.karandash.api.module.PipelineExtension.invalidateRules]); with it or
 * `--no-cache` the short-circuit is skipped so rules re-run.
 */
internal suspend fun findCachedFindings(
    mr: MergeRequest,
    extensions: List<PipelineExtension>,
    configStage: ConfigLoadStage,
    logger: Logger,
    options: ValidationOptions,
): List<Finding>? {
    val invalidatedRules = PipelineInfrastructure.parseInvalidatedRules(options.invalidateRules)
    if (invalidatedRules.isNotEmpty()) {
        extensions.forEach { ext -> runExtensionHook(ext, "invalidateRules", logger, Unit) { ext.invalidateRules(invalidatedRules) } }
    }
    if (options.noCache || invalidatedRules.isNotEmpty()) {
        logger.debug("Incremental cache: skipped (--no-cache or --invalidate-rules)")
        return null
    }
    val aiBlockElement = configStage.config.ai ?: return null
    return try {
        extensions.firstNotNullOfOrNull { it.getCachedFindings(mr, aiBlockElement, logger) }
    } catch (
        // boundary: third-party extension cache lookup must not fail the run
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        e.rethrowCancellation()
        logger.warn("Failed to check incremental cache: ${e.message}")
        null
    }
}

/** The enrichment providers' data and, per provider that was on, how it went for the review provenance. */
internal class EnrichmentRun(
    val context: EnrichmentContext,
    val enrichers: List<EnricherProvenance>,
    val timings: List<EnrichmentTiming> = emptyList(),
)

@Suppress("TooGenericExceptionCaught") // boundary: enrichment provider init is non-fatal
internal suspend fun executeEnrichmentProviders(
    configStage: ConfigLoadStage,
    mr: MergeRequest,
    moduleEnabledOverrides: Map<String, Boolean> = emptyMap(),
    logger: Logger,
): EnrichmentRun {
    val providers = configStage.registry.enrichmentProviders()
    if (providers.isEmpty()) return EnrichmentRun(EnrichmentContext.EMPTY, emptyList())
    // rows decided before the engine runs: init threw, or the provider reported why it is off or partly off
    val decided = mutableListOf<EnricherProvenance>()

    val providerConfigs = configStage.config.enrichmentProviders.orEmpty()
    val enrichmentTimeout = (configStage.config.timeouts ?: TimeoutConfig()).enrichment
    val registered = providers.mapNotNull { provider ->
        // policy `modules:` entries are camelCased at parse time, like the `enrichment:` keys below
        if (moduleEnabledOverrides[ConfigKeyNormalizer.kebabToCamelCase(provider.id)] == false ||
            moduleEnabledOverrides[provider.id] == false
        ) {
            logger.debug("Enrichment provider '${provider.id}': skipped by policy")
            return@mapNotNull null
        }
        // `enrichment:` keys are normalized to camelCase at parse time (`local-git` → `localGit`). The documented
        // key is the provider's configKey; its module id is still accepted.
        val providerConfig = listOf(provider.configKey, provider.id).firstNotNullOfOrNull { key ->
            providerConfigs[ConfigKeyNormalizer.kebabToCamelCase(key)] ?: providerConfigs[key]
        }
        if (isModuleDisabled(providerConfig) || (providerConfig == null && !provider.defaultEnabled)) {
            logger.debug("Enrichment provider '${provider.id}': disabled by config")
            return@mapNotNull null
        }
        val pluginConfig = providerConfig ?: JsonObject(emptyMap())
        val ctx = configStage.contexts.contextWithConfig(
            moduleId = provider.id,
            pluginConfig = pluginConfig,
        )
        // tracked before init, like rules: the run's close shuts down every provider it initialized
        configStage.session?.track(provider)
        val initialized = try {
            provider.initialize(ctx)
        } catch (e: Exception) {
            // boundary: enrichment provider initialize() may throw any exception
            e.rethrowCancellation()
            logger.warn("Enrichment provider '${provider.id}' init failed (non-fatal): ${e.message}")
            decided += EnricherProvenance(provider.id, ProvenanceStatus.FAILED, reason = INIT_FAILED_REASON)
            return@mapNotNull null
        }
        val skipReason = provider.skipReason()
            ?: INIT_REFUSED_REASON.takeIf { !initialized && providerConfig != null }
        skipReason?.let { decided += EnricherProvenance(provider.id, ProvenanceStatus.SKIPPED, reason = it) }
        if (!initialized) return@mapNotNull null
        RegisteredEnrichmentProvider(
            id = provider.id,
            module = provider,
            configElement = pluginConfig,
            timeout = enrichmentTimeout,
        )
    }
    if (registered.isEmpty()) return EnrichmentRun(EnrichmentContext.EMPTY, decided)
    val engine = EnrichmentEngine(registered, logger = logger)
    val context = engine.enrich(mr)
    val ran = registered.map { r ->
        val outcome = enricherOutcome(r.id, r.module, context, engine.timings.find { it.id == r.id })
        // a partly-off provider (skip reason) that then timed out or threw is failed, not skipped
        if (outcome.status == ProvenanceStatus.FAILED) outcome else decided.find { it.id == r.id } ?: outcome
    }
    val notRun = decided.filter { d -> registered.none { it.id == d.id } }
    return EnrichmentRun(context, (ran + notRun).sortedBy { it.id }, engine.timings)
}

/**
 * Failed when the engine marked any of its ids missing (timeout or throw); skipped when the provider learned while
 * enriching why it had nothing to give (local-git without git); else ok with the ids it delivered or cached, from
 * [timing]. No token count or duration: enrichment data can carry timestamps and durations vary, so either would
 * rewrite the MR comment between identical runs.
 */
private fun enricherOutcome(
    id: String,
    provider: EnrichmentProviderModule,
    context: EnrichmentContext,
    timing: EnrichmentTiming?,
): EnricherProvenance {
    val ids = provider.providedEnrichment
    if (ids.any { it in context.missingIds }) {
        return EnricherProvenance(id, ProvenanceStatus.FAILED, reason = "timed out or failed, see the log")
    }
    provider.skipReason()?.let { return EnricherProvenance(id, ProvenanceStatus.SKIPPED, reason = it) }
    return EnricherProvenance(id, ProvenanceStatus.OK, items = timing?.outputIds?.size ?: 0, cached = timing?.fromCache == true)
}

// the exception message can carry urls or tokens and the reason reaches the public MR comment: log only
private const val INIT_FAILED_REASON = "initialize failed, see the job log"
private const val INIT_REFUSED_REASON = "initialize returned false, see the job log"
