@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.context.EnrichmentContext
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.finding.EnricherProvenance
import io.karandash.api.finding.TimingData
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.FetchOptions
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.run.RunSession
import io.karandash.api.run.dumpJson
import io.karandash.api.run.dumpRun
import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.cli.commands.validate.ValidateModeResolver
import io.karandash.conditions.BuiltinPredicates
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RuleEnrichmentConfig
import io.karandash.config.pipeline.ModuleEnablementResolver
import io.karandash.engine.PolicyApplicationResult
import io.karandash.engine.PolicyApplicator
import io.karandash.helpers.RegexHelper
import io.karandash.http.NetworkPolicy
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.patterns.PatternAwareCompilerImpl
import io.karandash.patterns.PatternResolver
import io.karandash.patterns.PomskyCompilerFactory
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.absolutePathOrSelf
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.utils.DurationFormatter
import io.karandash.utils.EnrichmentGlobMatcher
import io.karandash.utils.GlobMatcher
import io.karandash.utils.SuggestionEngine
import io.karandash.utils.mr.ShortSha
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.measureTimedValue

/** What the preparation stages produced: the scope, extensions and (enriched) MR every later stage reads. */
internal class PreparedRun(
    val configStage: ConfigLoadStage,
    val scope: ValidateCommandScope,
    val extensions: List<PipelineExtension>,
    val fetchOptions: FetchOptions,
    val providerStage: ProviderStage,
    val mr: MergeRequest,
    val patternResolver: PatternResolver,
    val gating: RunGating,
)

/** Early policy evaluation, module enablement and enrichment-provider data for validation and reporting. */
internal class RunGating(
    val enabledModules: Map<String, Boolean>,
    val ciContext: CIContext,
    val policyResult: PolicyApplicationResult,
    val enrichmentContext: EnrichmentContext,
    val timingData: TimingData,
    val enrichers: List<EnricherProvenance> = emptyList(),
)

/**
 * Stages 1-3 of [ValidationPipeline]: config loading and run infrastructure, scope and rule-module
 * initialization, provider fetch, MR enrichment, early policy evaluation and enrichment providers.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class PipelinePreparationStage(
    private val options: ValidationOptions,
    private val configLoader: ConfigLoader,
    private val moduleInitializer: ModuleInitializer,
    private val fixAndResult: FixAndResultHandler,
    private val session: RunSession,
    private val resources: PipelineRunResources,
) {
    private val logger = options.logger

    /** Loads config and installs the run infrastructure (HTTP settings, compile limits, diagnostics, caches). */
    suspend fun setUp(): ConfigLoadStage {
        options.dumpDir?.let { dir -> DumpDirWriter.create(dir, logger)?.let { session.provide(RunServiceKeys.DUMP, it) } }
        val (configStage, configDuration) = measureTimedValue { configLoader.loadConfig(session) }
        logger.debug("Config loading: ${DurationFormatter.formatMs(configDuration.inWholeMilliseconds)}")
        dumpConfig(configStage)
        session.provide(ProviderHttpClientFactory.HTTP_SETTINGS, PipelineInfrastructure.httpClientSettings(configStage.config, logger))
        resources.clearCompileLimits = PipelineInfrastructure.installCompileLimits(configStage.config)

        resources.diag = PipelineDiagnosticBridge.createIfConfigured(
            session,
            options.diagnosticPath,
            configStage.config.diagnostics,
            configDuration.inWholeMilliseconds,
            logger,
        )

        val cacheConfig = configStage.config.cache ?: CacheConfig()
        val cacheDirPath = configLoader.resolveCacheDirPath(cacheConfig.dir)
        logger.debug("Cache directory: ${cacheDirPath.absolutePathOrSelf()}")
        // `--no-cache`: file-tier caches (AI responses, plugin caches) are written but never read back.
        val cacheManager = CacheManager(config = cacheConfig, cacheDir = cacheDirPath, logger = logger, readFiles = !options.noCache)
        resources.cacheManager = cacheManager
        GlobMatcher.useManaged(cacheManager)
        BuiltinPredicates.useManaged(cacheManager)
        ShortSha.configure(configStage.config.display.git.effectiveShortShaLength)
        val subprocessBudget = scriptSubprocessBudget(configStage.config)
        configStage.contexts.cacheProvider = cacheManager
        configStage.contexts.subprocessBudget = subprocessBudget
        session.provide(RunServiceKeys.CACHE_PROVIDER, cacheManager)
        session.provide(RunServiceKeys.SUBPROCESS_BUDGET, subprocessBudget)
        resources.remoteCache =
            PipelineInfrastructure.wireRemoteCache(
                cacheManager,
                cacheConfig,
                logger,
                configStage.contexts.allowLocalhost,
                configStage.configLayers,
                NetworkPolicy.allowsPrivateNetwork(),
            )
        PipelineInfrastructure
            .buildResponseCache(cacheManager, cacheConfig, configStage.config)
            ?.let { session.provide(ProviderHttpClientFactory.RESPONSE_CACHE, it) }

        if (configStage.config.rules.isEmpty()) {
            logger.warn("No validation rules configured")
        }
        return configStage
    }

    /** Creates the scope, initializes rule modules, fetches and enriches the MR, then gates the run. */
    suspend fun prepare(configStage: ConfigLoadStage): PreparedRun {
        val scope = configLoader.createScope(configStage).also { resources.scope = it }
        // PER_RUN extensions run as this run's own initialized handles; the registry prototypes stay untouched
        val extensions = configStage.registry
            .pipelineExtensions()
            .mapNotNull { session.handle(it, configStage.contexts.forModule(it.id)) }
        session.provide(RunServiceKeys.PIPELINE_EXTENSIONS, extensions)
        val patternResolver = initializeModules(configStage, scope, extensions)

        val fetchOptions = buildFetchOptions(configStage, extensions)
        val diag = resources.diag
        diag?.stageStart("providerFetch")
        val (providerStage, providerDuration) = measureTimedValue {
            val ai = configStage.config.ai
            resolveProvider(
                configStage,
                scope,
                fetchOptions,
                needsBlame = extensions.any { it.needsBlame(ai) },
                needsHeadContent = extensions.any { it.needsHeadContent(ai) },
            )
        }
        logger.debug("Provider fetch: ${DurationFormatter.formatMs(providerDuration.inWholeMilliseconds)}")
        if (options.verbose) {
            providerStage.provider?.rateLimitStatus()?.let { logger.debug("API rate limit: ${it.remaining}/${it.limit} remaining") }
        }
        diag?.stageEnd("providerFetch", providerDuration.inWholeMilliseconds)
        dumpJson("mr-fetched.json") { providerStage.mr }

        configLoader.configurePipelineExtensions(configStage, extensions, providerStage.provider?.id)
        resources.configuredExtensions = extensions

        deliverBaseFiles(extensions, providerStage, configStage.config.ai)
        for (ext in extensions) {
            runExtensionHook(ext, "onPostFetch", logger, Unit) { ext.onPostFetch(providerStage.mr, configStage.extensionConfig) }
        }

        diag?.stageStart("enrichment")
        val (enrichedMr, enrichDuration) = measureTimedValue {
            moduleInitializer.runEnrichment(
                configStage,
                scope,
                providerStage,
                fixAndResult::resolveModuleConfig,
                fetchOptions,
            )
        }
        if (enrichedMr !== providerStage.mr) {
            logger.debug("Enrichment: ${DurationFormatter.formatMs(enrichDuration.inWholeMilliseconds)}")
        }
        diag?.stageEnd("enrichment", enrichDuration.inWholeMilliseconds)
        dumpJson("mr-enriched.json") { enrichedMr }

        val gating = gate(configStage, enrichedMr, patternResolver)
        val knownRuleIds = knownRuleIds(configStage)
        val ruleEnrichmentBindings = canonicalRuleEnrichment(configStage.config.ruleEnrichment.orEmpty(), knownRuleIds)
        warnUnmatchedRuleEnrichment(configStage, ruleEnrichmentBindings, knownRuleIds, logger)
        moduleInitializer.deliverEnrichmentToRuleModules(
            configStage,
            gating.enrichmentContext,
            ruleEnrichmentBindings,
            extensions,
        )
        return PreparedRun(configStage, scope, extensions, fetchOptions, providerStage, enrichedMr, patternResolver, gating)
    }

    /**
     * Injects the state store, builds the pattern registry and initializes the configured rule modules.
     * Returns the run's [PatternResolver].
     */
    private suspend fun initializeModules(
        configStage: ConfigLoadStage,
        scope: ValidateCommandScope,
        extensions: List<PipelineExtension>,
    ): PatternResolver {
        val cacheManager = checkNotNull(resources.cacheManager) { "cache manager is created in setUp()" }
        val cacheConfig = configStage.config.cache ?: CacheConfig()
        // Inject the state store into state-persisting modules before initialize() is called.
        val stateStore = PipelineInfrastructure.buildStateStore(cacheConfig, cacheManager, options.noCache)
        PipelineInfrastructure.injectStateStore(stateStore, configStage.registry, extensions)

        // Build pattern registry from core built-ins and all registered module patterns,
        // then inject into RegexHelper so all validators gain pattern-system support.
        // before rule init, so rules compiling patterns in initialize() see them.
        val patternRegistry = ConfigPatterns.registry(configStage.config.patterns, configStage.registry)
        val patternResolver = PatternResolver.createManaged(
            registry = patternRegistry,
            cacheManager = cacheManager,
            pomskyCompiler = PomskyCompilerFactory.create(),
        )
        ConfigPatterns.errors(configStage.config.patterns, patternResolver).firstOrNull()?.let {
            throw ConfigParseException(it)
        }
        configStage.contexts.patternResolver = patternResolver
        resources.patternCompiler =
            PatternAwareCompilerImpl(patternResolver).also {
                RegexHelper.injectPatternCompiler(it)
                session.provide(RunServiceKeys.PATTERN_COMPILER, it)
            }

        moduleInitializer.initializeConfiguredRuleModules(configStage, scope, extensions, session)

        val configuredRuleIds = configStage.config.rules.ruleConfigs.keys
        logger.debug(
            "Configured rules (${configuredRuleIds.size}): ${configuredRuleIds.joinToString(", ")}"
        )
        return patternResolver
    }

    /** Module enablement, early policy evaluation (module and stage gating) and enrichment providers. */
    private suspend fun gate(
        configStage: ConfigLoadStage,
        mr: MergeRequest,
        patternResolver: PatternResolver,
    ): RunGating {
        val enabledModules = ModuleEnablementResolver(logger).resolveFromConfig(configStage.config)
        val ciContext = fixAndResult.resolveCIContext(configStage.registry.platformServices())
        val policyResult = evaluatePolicies(
            configStage = configStage,
            mr = mr,
            parsedPolicies = configStage.config.policies ?: emptyList(),
            ciPlatform = ciContext.platform,
            isCI = ciContext.isCI,
            patternExpander = { pattern -> patternResolver.resolveNested(pattern) },
        )

        val (enrichmentRun, enrichmentProviderDuration) = measureTimedValue {
            executeEnrichmentProviders(configStage, mr, policyResult.moduleEnabledOverrides, logger)
        }
        val enrichmentContext = enrichmentRun.context
        enrichmentRun.timings.forEach { resources.diag?.recordEnrichmentResult(it.id, it.durationMs, it.fromCache, it.outputIds) }
        session.publishEnrichment(enrichmentContext)
        if (enrichmentContext.availableIds.isNotEmpty()) {
            logger.debug(
                "Enrichment providers: ${DurationFormatter.formatMs(enrichmentProviderDuration.inWholeMilliseconds)}" +
                    " (${enrichmentContext.availableIds.size} data entries)"
            )
        }
        return RunGating(
            enabledModules = enabledModules,
            ciContext = ciContext,
            policyResult = policyResult,
            enrichmentContext = enrichmentContext,
            timingData = TimingData(enrichmentFetchTimeMs = enrichmentProviderDuration.inWholeMilliseconds),
            enrichers = enrichmentRun.enrichers,
        )
    }

    /** Reads each extension's [PipelineExtension.baseFiles] at the MR base and hands them to `onBaseFiles`. */
    private suspend fun deliverBaseFiles(
        extensions: List<PipelineExtension>,
        stage: ProviderStage,
        ai: JsonElement?,
    ) {
        val provider = stage.provider
        val projectId = stage.projectId
        for (ext in extensions) {
            val paths = ext.baseFiles(ai)
            if (paths.isEmpty()) continue
            val files = if (provider != null && projectId != null) {
                withBaseFiles(stage.mr, provider, projectId, paths, logger)
            } else {
                emptyMap()
            }
            runExtensionHook(ext, "onBaseFiles", logger, Unit) { ext.onBaseFiles(files) }
        }
    }

    private suspend fun resolveProvider(
        configStage: ConfigLoadStage,
        scope: ValidateCommandScope,
        fetchOptions: FetchOptions = FetchOptions(),
        needsBlame: Boolean = false,
        needsHeadContent: Boolean = false,
    ): ProviderStage {
        options.mrOverride?.let { return ProviderStage(provider = null, mr = it) }
        // Re-initialize the provider with actual credentials from the parsed config.
        // During bootstrap, providers register without credentials (tokens/URLs are not
        // yet available). This callback runs after the provider module is resolved but
        // before the first fetch, giving the provider a chance to create its HTTP client.
        val providerInitializer: suspend (PlatformServiceModule, String?) -> Unit = { provider, prBaseUrl ->
            val configured = configStage.config.platformConfig(provider.configKey) as? JsonObject ?: JsonObject(emptyMap())
            val providerConfig = withPrBaseUrl(configured, prBaseUrl.takeIf { provider.defaultApiUrl() != null })
            moduleInitializer.initializeRuntimeModule(configStage, provider, providerConfig)
        }

        val modeResolution = ValidateModeResolver(
            mrDataPath = options.mrDataStr,
            mrId = options.mrId,
            platform = options.platform,
            project = options.project,
            prUrl = options.prUrl,
            noAutoDetect = options.noAutoDetect,
            logger = logger,
            readMrFileContent = fixAndResult::readMrFileContent,
            providerRuntime = scope.providerRuntime,
            providerInitializer = providerInitializer,
            configuredTokenVar = { provider -> provider?.let { configuredTokenVar(configStage.configLayers, it.configKey) } },
            fetchOptions = fetchOptions,
        ).resolve(
            registry = scope.registry,
            preferredPluginIds = scope.preferredPluginIds
        )

        val provider = modeResolution.provider
        val projectId = modeResolution.projectId
        val mr = if (provider != null && projectId != null) {
            val fetched = modeResolution.mr
            val withContent = if (needsHeadContent) withHeadContent(fetched, provider, projectId, logger) else fetched
            if (needsBlame) withBlame(withContent, provider, projectId, logger) else withContent
        } else {
            modeResolution.mr
        }
        return ProviderStage(
            provider = provider,
            mr = mr,
            projectId = projectId,
            mrId = modeResolution.mrId,
        )
    }

    /**
     * Builds [FetchOptions] from the first extension that offers hints. When none does,
     * returns the default (3 context lines).
     */
    private fun buildFetchOptions(
        configStage: ConfigLoadStage,
        extensions: List<PipelineExtension>,
    ): FetchOptions = extensions.firstNotNullOfOrNull { it.fetchOptions(configStage.config.ai) } ?: FetchOptions()
}

/** `--dump-dir`: the raw config, the parsed effective config and the config extension hooks get. */
private suspend fun dumpConfig(configStage: ConfigLoadStage) {
    dumpJson("config/raw.json") { configStage.rawElement }
    dumpRun("config/effective.json") { KarandashJson.PrettyWithDefaults.encodeToString(Config.serializer(), configStage.config) }
    dumpJson("config/extension.json") { configStage.extensionConfig }
}

/** Every rule id a module contributes, plus the AI analyzers (review-comments, ...), which the AI module contributes as rules. */
private suspend fun knownRuleIds(configStage: ConfigLoadStage): Set<String> =
    configStage.registry
        .ruleModules()
        .flatMap { it.contributedRules() }
        .toSet() +
        configStage.registry.aiAnalyzers().map { it.analyzerId }

/**
 * Re-keys `rule-enrichment:` bindings by the contributed rule id they name in any notation (`jira-ticket`, `jiraTicket`),
 * so the exact lookup in [ModuleInitializer] finds them; bindings naming one rule twice merge.
 */
private fun canonicalRuleEnrichment(
    bindings: Map<String, RuleEnrichmentConfig>,
    knownRuleIds: Set<String>,
): Map<String, RuleEnrichmentConfig> =
    bindings.entries
        .groupBy({ (id, _) -> ModuleIdNormalizer.resolve(id, knownRuleIds) ?: id }, { it.value.enrich })
        .mapValues { (_, lists) -> RuleEnrichmentConfig(lists.flatten().distinct()) }

/** Warns about `rule-enrichment:` bindings naming an unknown rule or matching no known enrichment ID. */
private fun warnUnmatchedRuleEnrichment(
    configStage: ConfigLoadStage,
    ruleEnrichmentBindings: Map<String, RuleEnrichmentConfig>,
    knownRuleIds: Set<String>,
    logger: Logger,
) {
    if (ruleEnrichmentBindings.isEmpty()) return
    val knownIds = configStage.registry.registeredIds() + knownRuleIds
    val knownEnrichmentIds = configStage.registry
        .enrichmentProviders()
        .flatMap { it.providedEnrichment }
        .toSet()
    for ((ruleId, binding) in ruleEnrichmentBindings) {
        if (ruleId !in knownIds) {
            val hint = SuggestionEngine.closest(ruleId, knownIds)?.let { " (did you mean '$it'?)" }.orEmpty()
            logger.warn("rule-enrichment: unknown rule id '$ruleId' — binding will be ignored$hint")
        }
        for (pattern in binding.enrich) {
            if (!EnrichmentGlobMatcher.matchesAny(pattern, knownEnrichmentIds)) {
                logger.warn(
                    "rule-enrichment.$ruleId: '$pattern' matches no known enrichment ID " +
                        "(known: ${knownEnrichmentIds.sorted().joinToString(", ")})",
                )
            }
        }
    }
}

/**
 * Evaluates policies early in the pipeline for module and stage gating.
 *
 * Creates a [PolicyApplicator] with enriched environment context and evaluates
 * all parsed policies against the fetched MR. The result provides module-level
 * enablement overrides and stage gating booleans.
 *
 * Scope: this runs after rule-module initialization, `onPostFetch` and MR enrichment, so its
 * `enabled` overrides gate only enrichment providers, reporters/outputs and the stage flags; rules are
 * gated by the engine's own policy pass (they are still initialized). Pipeline extensions, platform
 * services and MR enrichment cannot be switched off by a policy.
 *
 * Silent: the engine evaluates the same policies again and logs that resolution, so the log shows it once.
 */
private suspend fun evaluatePolicies(
    configStage: ConfigLoadStage,
    mr: MergeRequest,
    parsedPolicies: List<PolicyConfig>,
    ciPlatform: String?,
    isCI: Boolean,
    patternExpander: ((String) -> String)? = null,
): PolicyApplicationResult {
    if (parsedPolicies.isEmpty()) {
        return PolicyApplicationResult(
            runtimeConfig = configStage.config,
            activePolicies = emptyList(),
            enabledOverrides = emptyMap(),
        )
    }
    val applicator = PolicyApplicator(
        config = configStage.config,
        registry = configStage.registry,
        policyEngine = configStage.config.limits.let {
            DefaultPolicyEngine(
                NoOpLogger,
                patternExpander,
                it.expressions.effectiveMaxLength,
                it.expressions.effectiveMaxNestingDepth,
                it.regex.effectiveMaxPatternLength,
            )
        },
        policies = parsedPolicies,
        logger = NoOpLogger,
        envLookup = KarandashEnvSecurity.withoutSecrets(PlatformEnvironment::getEnv),
        ciPlatform = ciPlatform,
        isCI = isCI,
    )
    return applicator.applyPolicies(mr)
}

/** Platform config keys that name the server URL. */
private val URL_KEYS = listOf("url", "baseUrl")

/**
 * [configured] with the `--pr` URL's [prBaseUrl] as its `url`: a `--pr` URL on another host (self-hosted GitLab) beats
 * the platform's default URL, never an explicit `url` / `baseUrl`. Works with no `platforms:` block at all.
 */
internal fun withPrBaseUrl(
    configured: JsonObject,
    prBaseUrl: String?,
): JsonObject =
    if (prBaseUrl != null && URL_KEYS.none(configured::containsKey)) {
        JsonObject(configured + ("url" to JsonPrimitive(prBaseUrl)))
    } else {
        configured
    }

private val ENV_REFERENCE = Regex("""^\$\{(\w+)}$""")

/** The env var named by `platforms.<[configKey]>.token: ${VAR}` in the raw [layers], nearest layer first. */
internal fun configuredTokenVar(
    layers: List<Pair<String, JsonElement>>,
    configKey: String,
): String? =
    layers.asReversed().firstNotNullOfOrNull { (_, element) ->
        val platforms = (element as? JsonObject)?.get("platforms") as? JsonObject ?: return@firstNotNullOfOrNull null
        val block = ModuleIdNormalizer.resolve(configKey, platforms.keys)?.let(platforms::get) as? JsonObject
        val token = (block?.get("token") as? JsonPrimitive)?.takeIf { it.isString }?.content
        token?.let { ENV_REFERENCE.matchEntire(it)?.groupValues?.get(1) }
    }
