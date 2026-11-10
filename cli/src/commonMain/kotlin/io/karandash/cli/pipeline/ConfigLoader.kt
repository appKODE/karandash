@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiServiceKeys
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.run.RunSession
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.bootstrap.RunPluginContexts
import io.karandash.cli.GlobalWatchdog
import io.karandash.cli.ai.AiHostBindings
import io.karandash.cli.commands.cache.expandCacheDir
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.cli.commands.validate.ValidateConfigPipeline
import io.karandash.cli.commands.validate.localFiles
import io.karandash.cli.plugin.PLUGIN_MANIFEST_PATH
import io.karandash.cli.plugin.manifestFromConfig
import io.karandash.cli.plugin.readPluginManifest
import io.karandash.config.Config
import io.karandash.config.ProfileOverridesConfig
import io.karandash.config.ProfileRef
import io.karandash.config.aiProviderId
import io.karandash.config.pipeline.AuthBlockParser
import io.karandash.config.pipeline.ConditionExpressionCheck
import io.karandash.config.pipeline.ConfigDiscovery
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ExternalValueResolver
import io.karandash.config.pipeline.JsonElementMerger
import io.karandash.config.pipeline.MissingModuleDetector
import io.karandash.config.pipeline.ProfileResolver
import io.karandash.config.pipeline.QuotedScalarCoercion
import io.karandash.config.pipeline.RemoteConfigFetcher
import io.karandash.config.pipeline.ReportLanguage
import io.karandash.config.pipeline.RuleConfigDecodeCheck
import io.karandash.config.pipeline.SchemaCoercer
import io.karandash.config.pipeline.SsrfMode
import io.karandash.config.pipeline.UnknownNestedKeys
import io.karandash.config.pipeline.UnknownTopLevelKeys
import io.karandash.config.pipeline.ZeroConfigDefaults
import io.karandash.config.pipeline.asContentFetcher
import io.karandash.config.schema.ConfigSections
import io.karandash.content.AuthRegistry
import io.karandash.content.ContentResolver
import io.karandash.content.GitRepoFileFetcher
import io.karandash.detect.CIDetector
import io.karandash.logger.debug
import io.karandash.manifest.ManifestData
import io.karandash.manifest.MissingPluginBehavior
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.normalize.deepNormalizeKeys
import io.karandash.platform.getCurrentWorkingDirectoryString
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.sdk.Logger
import io.karandash.sdk.content.ContentSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles config loading, parsing, profile resolution, and scope creation.
 *
 * Extracted from [ValidationPipeline] to isolate config pipeline logic.
 *
 * @lifecycle per-run
 */
internal class ConfigLoader(
    private val options: ValidationOptions,
    private val sharedBootstrap: PluginBootstrap,
    private val logger: Logger,
    /** Reads the config file itself; the language server passes the editor buffer. */
    private val readFile: ((Path) -> String)? = null,
) {
    /** a rule config its module cannot decode used to run on defaults with an INFO finding. */
    private suspend fun checkRuleConfigs(
        config: Config,
        registry: ModuleRegistry,
    ) {
        val errors = RuleConfigDecodeCheck.errors(config.rules, registry)
        if (errors.isNotEmpty()) {
            throw ConfigParseException(
                errors.joinToString("\n"),
                hint = "Fix the rule's value in the config; 'karandash config describe' shows its effective settings.",
            )
        }
    }

    /** Every layer (parents and root): an unknown key in a parent is ignored just like the root's. */
    private suspend fun checkUnknownKeys(
        rawLayers: List<JsonElement>,
        config: Config,
        cleanedElement: JsonElement,
        registry: ModuleRegistry,
    ) {
        val unknownKeyWarnings = rawLayers
            .flatMap { UnknownTopLevelKeys.warnings(it, config.extensions.keys) }
            .distinct() + UnknownNestedKeys.warnings(cleanedElement, config.rules, registry, config.extensions)
        // a typo'd key is only a warning by default; --strict makes it stop the run
        if (options.strict && unknownKeyWarnings.isNotEmpty()) {
            throw ConfigException("Config has unknown keys (--strict):\n" + unknownKeyWarnings.joinToString("\n"))
        }
        unknownKeyWarnings.forEach(logger::warn)
    }

    /** an unknown predicate or malformed `when` used to turn its policy or rule off at run time, silently. */
    private fun checkConditions(
        config: Config,
        registry: ModuleRegistry,
    ) {
        val errors = ConditionExpressionCheck.errors(config, registry)
        if (options.strict && errors.isNotEmpty()) {
            throw ConfigException("Config has invalid 'when' expressions (--strict):\n" + errors.joinToString("\n"))
        }
        errors.forEach { logger.warn("$it. The policy or rule never applies until it is fixed; this becomes an error in a later release.") }
    }

    /** Unresolved `{ file: }` references are stripped first: the resolver already warned about them. */
    private suspend fun checkSchema(
        cleanedElement: JsonElement,
        registry: ModuleRegistry,
    ) {
        val coerced = SchemaCoercer(registry).coerce(ExternalValueResolver.withoutFileReferences(cleanedElement))
        val schemaWarnings = coerced.warnings.map { "${it.moduleId}: ${it.message}" }
        if (options.strict && schemaWarnings.isNotEmpty()) {
            throw ConfigException("Config does not match the module schema (--strict):\n" + schemaWarnings.joinToString("\n"))
        }
        schemaWarnings.forEach { logger.warn("[SCHEMA] $it") }
    }

    suspend fun loadConfig(session: RunSession? = null): ConfigLoadStage {
        val layers = mutableListOf<Pair<String, JsonElement>>()
        var extendsChainFiles = emptyList<String>()
        val pipeline = createConfigPipeline { url, element -> layers += url to element }
        val bootstrapResult = pipeline.loadConfigBootstrap()
        logger.debug(
            "Config loaded: path=${bootstrapResult.configFilePath ?: "<zero-config>"}, zeroConfig=${bootstrapResult.usingZeroConfig}"
        )

        val shared = options.sharedBootstrap
        val registry = shared.registry()
        val bootstrap: PluginBootstrap = shared
        // run-scoped contexts; `allow-private-network` (set while loading) lives in the run's NetworkPolicy
        val contexts = bootstrap.newRunContexts()

        val finalConfig: io.karandash.config.Config
        // merged extends chain with missing-module sections stripped: what the run actually uses
        var effectiveElement: JsonElement = bootstrapResult.rawElement
        var missingEnvVars = emptySet<String>()
        if (bootstrapResult.usingZeroConfig) {
            logger.debug("Using zero-config defaults")
            finalConfig = ZeroConfigDefaults.defaultConfig(registry, logger)
        } else {
            val configFilePath = checkNotNull(bootstrapResult.configFilePath) { "Config file path missing for a non-zero config" }
            val cleanedElement = runMissingPluginDetection(
                pipeline.mergeChain(
                    bootstrapResult.rawElement,
                    configFilePath,
                    registry = registry,
                    onChain = { extendsChainFiles = it.localFiles() },
                ),
                bootstrapResult.rawElement,
                registry,
                configFilePath,
            )
            effectiveElement = cleanedElement
            val parseResult = pipeline.parseMerged(cleanedElement, registry)
            finalConfig = parseResult.config
            missingEnvVars = parseResult.missingEnvVars
            if (parseResult.missingEnvVars.isNotEmpty()) {
                logger.debug("Interpolation: ${parseResult.missingEnvVars.size} missing env var(s)")
            }
            parseResult.missingEnvVars.forEach { varName ->
                logger.warn(
                    "${KarandashError.ENV_VAR_NOT_SET.bracketCode} Environment variable '$varName' is referenced in config but not set.",
                )
            }
            checkRuleConfigs(finalConfig, registry)
            checkConditions(finalConfig, registry)
            QuotedScalarCoercion.warnings(cleanedElement, registry).forEach(logger::warn)
            checkSchema(cleanedElement, registry)
            val normalizedLayers = layers.map { it.second }.ifEmpty { listOf(bootstrapResult.rawElement) }.map(::deepNormalizeKeys)
            ReportLanguage.warnings(normalizedLayers).forEach(logger::warn)
            if (!options.skipUnknownKeyWarnings) {
                val rawLayers = layers.map { it.second }.ifEmpty { listOf(bootstrapResult.rawElement) }
                checkUnknownKeys(rawLayers, finalConfig, cleanedElement, registry)
            }
        }

        contexts.aiService = if (options.applyRunSettings && registry.aiProviders().isNotEmpty()) {
            createAiService(registry, contexts, finalConfig.aiProviderId(), session)
        } else {
            null
        }

        // --profile replaces only the id: the config's profile overrides and severity still apply
        val profileRef = options.profileId?.let { id ->
            finalConfig.profile?.copy(id = id) ?: ProfileRef(id = id)
        } ?: finalConfig.profile

        if (profileRef != null) logger.debug("Profile applied: ${profileRef.id}") else logger.debug("No profile applied")
        if (profileRef == null && finalConfig.profileOverrides != ProfileOverridesConfig()) {
            logger.warn("profileOverrides is set but no profile is active; it has no effect")
        }

        val resolvedConfig = applyExtensionExclusions(
            applyRuleSelection(
                applyDryRunScope(
                    if (profileRef != null) {
                        ProfileResolver(registry, logger).apply(finalConfig, profileRef, inheritedRuleIds(layers))
                    } else {
                        finalConfig
                    },
                ),
            ),
            registry,
        )

        contexts.allowLocalhost = options.allowLocalhost || resolvedConfig.allowLocalhost
        contexts.configDir = bootstrapResult.configFilePath?.safeParent?.toString() ?: getCurrentWorkingDirectoryString()
        val authRegistry = AuthBlockParser.parse(mergedAuthBlocks(bootstrapResult.rawElement, layers), logger = logger)
        contexts.contentResolver = hostContentResolver(
            contexts.configDir,
            authRegistry,
            contexts.allowLocalhost,
            resolvedConfig.limits.effectiveFetchTimeout.millis,
            logger,
        )

        logConfigSummary(resolvedConfig, profileRef?.id)
        logger.debug { EffectiveConfigDescriber.describe(resolvedConfig, layers).joinToString("\n", "Effective config:\n") }
        (
            resolvedConfig.limits.boundsWarnings() + resolvedConfig.display.boundsWarnings() + resolvedConfig.http.boundsWarnings()
        ).forEach(logger::warn)
        resolvedConfig.timeouts?.takeIf { options.applyRunSettings }?.let { timeouts ->
            bootstrap.configureShutdownTimeout(timeouts.pluginShutdown.toKotlinDuration())
            GlobalWatchdog.rearmActive(timeouts.global.toKotlinDuration())
        }

        return ConfigLoadStage(
            config = resolvedConfig,
            configFilePath = bootstrapResult.configFilePath?.toString(),
            usingZeroConfig = bootstrapResult.usingZeroConfig,
            registry = registry,
            bootstrap = bootstrap,
            contexts = contexts,
            rawElement = effectiveElement,
            authRegistry = authRegistry,
            configLayers = layers,
            extendsChainFiles = extendsChainFiles,
            session = session,
            missingEnvVars = missingEnvVars,
        )
    }

    /**
     * Rule ids (camelCase) that only `extends:` parents set: the root file (last layer) does not.
     * The profile wins over these (docs order: local `rules:` > profile > inherited > zero-config).
     */
    private fun inheritedRuleIds(layers: List<Pair<String, JsonElement>>): Set<String> {
        fun ruleIds(element: JsonElement): Set<String> =
            ((element as? JsonObject)?.get(RULES_KEY) as? JsonObject)
                ?.keys
                ?.map(ConfigKeyNormalizer::kebabToCamelCase)
                ?.toSet()
                .orEmpty()
        val root = layers.lastOrNull()?.second ?: return emptySet()
        return layers.dropLast(1).flatMap { (_, element) -> ruleIds(element) }.toSet() - ruleIds(root)
    }

    /**
     * `dry-run: true` turns AI dry-run on (every scope covers AI): `dryRun` is set in the `ai:` block, which
     * every AI reader (rule config, `AiPluginConfig.dryRunAi`, thread commands) already honors. Without
     * `dry-run-scope` it warns once, since before it only set `is_dry_run`.
     */
    private fun applyDryRunScope(config: io.karandash.config.Config): io.karandash.config.Config {
        if (options.showPrompt) return showPromptConfig(config)
        if (!config.dryRun) return config
        // No `ai:` block means no AI module runs; adding one here would turn it on.
        val ai = config.ai as? JsonObject ?: return config
        if (config.dryRunScope == null) {
            logger.warn(
                "dry-run without dry-run-scope now makes an AI dry run (no model calls, no thread replies); " +
                    "set dry-run-scope: all to also skip outputs",
            )
        }
        return config.copy(ai = JsonObject(ai + (AI_DRY_RUN_KEY to JsonPrimitive(true))))
    }

    /** `--dry-run --show-prompt`: `dry-run-scope: all` plus AI dry run with `ai.debug.prompt: artifact`. */
    private fun showPromptConfig(config: io.karandash.config.Config): io.karandash.config.Config {
        val scoped = config.copy(dryRun = true, dryRunScope = io.karandash.config.DryRunScope.ALL)
        val ai = config.ai as? JsonObject ?: return scoped
        val debug = ai[AI_DEBUG_KEY] as? JsonObject ?: JsonObject(emptyMap())
        val dumped = JsonObject(debug + (AI_DEBUG_PROMPT_KEY to JsonPrimitive(PROMPT_DUMP_ARTIFACT)))
        return scoped.copy(ai = JsonObject(ai + (AI_DRY_RUN_KEY to JsonPrimitive(true)) + (AI_DEBUG_KEY to dumped)))
    }

    /**
     * Keeps only the rules named by [ValidationOptions.onlyRules]; warns for a name that matches no configured rule.
     * Policy `override:` entries for the other rules are dropped too: an override recreates a rule's config, which
     * would run a rule `--rules` left out.
     */
    private fun applyRuleSelection(config: io.karandash.config.Config): io.karandash.config.Config {
        val wanted = options.onlyRules?.associateBy(ModuleIdNormalizer::normalize) ?: return config
        val configured = config.rules.ruleConfigs
        wanted.filterKeys { id -> configured.keys.none { ModuleIdNormalizer.normalize(it) == id } }.values.forEach {
            logger.warn("--rules: '$it' is not a configured rule")
        }
        val kept = configured.filterKeys { ModuleIdNormalizer.normalize(it) in wanted }
        val policies = config.policies?.map { policy ->
            policy.override?.let { override ->
                policy.copy(override = JsonObject(override.filterKeys { ModuleIdNormalizer.normalize(it) in wanted }))
            } ?: policy
        }
        return config.copy(rules = config.rules.copy(ruleConfigs = kept), policies = policies)
    }

    /** drops the configured rules a pipeline extension excludes (an execution profile), so they never run. */
    private fun applyExtensionExclusions(
        config: io.karandash.config.Config,
        registry: io.karandash.api.ModuleRegistry,
    ): io.karandash.config.Config {
        val configured = config.rules.ruleConfigs
        val excluded = registry.pipelineExtensions().flatMap { it.excludedRules(config.ai, configured.keys) }.toSet()
        if (excluded.isEmpty()) return config
        logger.info("Execution profile: not running ${excluded.sorted().joinToString(", ")}")
        return config.copy(rules = config.rules.copy(ruleConfigs = configured - excluded))
    }

    /** With a [session], providers resolve per run from the published `ai:` config. */
    private fun createAiService(
        registry: io.karandash.api.ModuleRegistry,
        contexts: RunPluginContexts,
        defaultProviderId: String?,
        session: RunSession?,
    ): AiService {
        val source = session?.let { run ->
            AiHostBindings.providerSource(run, registry) { cfg -> contexts.contextWithConfig(AI_SERVICE_CONTEXT_ID, cfg) }
        }
        val service = AiServiceImpl(registry, defaultProviderId, debugLogger = logger::debug, providerSource = source)
        if (session != null && source != null) {
            session.provide(AiServiceKeys.PROVIDER_SOURCE, source)
            session.provide(AiServiceKeys.SERVICE, service)
        }
        return service
    }

    private fun logConfigSummary(
        config: io.karandash.config.Config,
        profileId: String?,
    ) {
        val rulesCount = config.rules.ruleConfigs.keys.size
        val policiesCount = config.policies?.size ?: 0
        val patternsCount = config.patterns.size
        val hasAi = config.ai != null
        logger.debug(
            "Config summary: rules=$rulesCount, policies=$policiesCount, " +
                "patterns=$patternsCount, ai=$hasAi, " +
                "profile=${profileId ?: "none"}",
        )
    }

    fun resolveCacheDirPath(cacheDir: String): Path = expandCacheDir(cacheDir)

    suspend fun configurePipelineExtensions(
        configStage: ConfigLoadStage,
        extensions: List<PipelineExtension>,
        platformId: String?,
    ) {
        val env = PipelineEnvironment(
            aiService = configStage.contexts.aiService,
            rawConfig = configStage.extensionConfig,
            statsDir = AiHostBindings.statsDir(configStage.config.ai),
            platformId = platformId,
        )
        for (ext in extensions) {
            runExtensionHook(ext, "onConfigured", logger, Unit) { ext.onConfigured(env) }
        }
    }

    fun createScope(configStage: ConfigLoadStage): ValidateCommandScope =
        ValidateCommandScope(
            config = configStage.config,
            logger = logger,
            existingRegistry = configStage.registry,
            existingBootstrap = configStage.bootstrap,
        )

    /** Checks [mergedElement] for modules that are not installed; the `plugins:` fallback is read from the root [rawElement]. */
    private suspend fun runMissingPluginDetection(
        mergedElement: JsonElement,
        rawElement: JsonElement,
        registry: io.karandash.api.ModuleRegistry,
        configFilePath: Path? = null,
    ): JsonElement {
        // A manifest.yaml wins; otherwise the config file's own `plugins:` block (same schema) is used.
        val manifestData = readManifestData(configFilePath) ?: manifestFromConfig(rawElement)
        logger.debug("Manifest data: ${if (manifestData == ManifestData()) "default (no plugins declared)" else "loaded"}")
        val behavior = options.onMissingPlugin
            // the flag is a Clikt choice; a value it cannot be is a programming error, not a silent manifest fallback
            ?.let { checkNotNull(MissingPluginBehavior.fromString(it)) { "unknown on-missing-plugin '$it'" } }
            ?: manifestData.onMissing.takeUnless { options.strict }
            // a misspelled rule or module name is an unknown key too, so --strict fails on it
            ?: MissingPluginBehavior.FAIL
        val detector = MissingModuleDetector(
            registry = registry,
            manifestData = manifestData,
            behavior = behavior,
            warn = { msg -> logger.warn(msg) },
        )
        return detector.check(mergedElement)
    }

    /** The first `manifest.yaml` next to the config or under `.karandash/`, or `null` when none exists. */
    private suspend fun readManifestData(configFilePath: Path?): ManifestData? =
        listOfNotNull(configFilePath?.safeParent?.let { Path(it, "manifest.yaml") }, PLUGIN_MANIFEST_PATH)
            .firstOrNull { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
            ?.let { path -> readPluginManifest(path) { logger.warn("$it; no modules are treated as required") } }

    private suspend fun createConfigPipeline(onConfigLayer: (String, JsonElement) -> Unit): ValidateConfigPipeline =
        ValidateConfigPipeline(
            configPathStr = options.configPathStr,
            logger = logger,
            formatRegistry = options.sharedBootstrap.formatRegistry,
            onConfigLayer = onConfigLayer,
            allowLocalhost = options.allowLocalhost,
            missingFilesWarn = options.missingFilesWarn,
            platformName = CIDetector(options.sharedBootstrap.registry().platformServices()).detect()?.platform,
            readFile = readFile,
            cacheDownloads = !options.noCache,
        )

    companion object {
        /**
         * The one way a command other than `validate` loads its config: the full `validate` path (extends chain,
         * `{ file: }`, `env:` interpolation, missing plugins, profile), without the run settings.
         */
        fun forCommand(
            configPath: String?,
            bootstrap: PluginBootstrap,
            logger: Logger = bootstrap.logger,
            missingFilesWarn: Boolean = false,
            strict: Boolean = false,
            readFile: ((Path) -> String)? = null,
        ): ConfigLoader =
            ConfigLoader(ValidationOptions.configOnly(configPath, bootstrap, missingFilesWarn, strict), bootstrap, logger, readFile)

        /** The one config file discovery: the nearest config file upwards from [startDir]. */
        fun discover(
            formatRegistry: ConfigFormatRegistry,
            startDir: Path = ConfigDiscovery.defaultStartDir(),
        ): ConfigDiscovery.DiscoveryResult = ConfigDiscovery(formatRegistry).discover(startDir)

        /** Logging id of the contexts AI providers get when resolved through the run. */
        private const val AI_SERVICE_CONTEXT_ID = "ai-service"
        private const val RULES_KEY = "rules"
        private const val AI_DRY_RUN_KEY = "dryRun"
        private const val AI_DEBUG_KEY = "debug"
        private const val AI_DEBUG_PROMPT_KEY = "prompt"
        private const val PROMPT_DUMP_ARTIFACT = "artifact"
    }
}

/**
 * Backs `PluginContext.resolveContent`: local files confined to [configDir], remote ones fetched
 * under the strict SSRF policy with the `auth:` block's credentials, repo files read from the
 * local checkout via `git show`. A fetcher per call keeps nothing open between plugin calls.
 */
internal fun hostContentResolver(
    configDir: String,
    authRegistry: AuthRegistry,
    allowLocalhost: Boolean,
    fetchTimeoutMs: Long,
    logger: Logger,
): suspend (ContentSource) -> String =
    { source ->
        val fetcher = RemoteConfigFetcher(
            ssrfMode = SsrfMode.STRICT,
            allowLocalhost = allowLocalhost,
            logger = logger,
            fetchTimeoutMs = fetchTimeoutMs,
        )
        try {
            ContentResolver(
                configDir = configDir,
                authRegistry = authRegistry,
                fetcher = fetcher.asContentFetcher(),
                logger = logger,
                repoFileFetcher = GitRepoFileFetcher(getCurrentWorkingDirectoryString()),
            ).resolve(source)
        } finally {
            fetcher.close()
        }
    }

/**
 * The `auth:` and `env:` blocks of the root and every `extends:` parent (local or remote), deep-merged (root wins),
 * and every layer's `env:` defaults still interpolate the merged `auth:`. Per host, the destination and credential
 * fields ([AUTH_BOUND_FIELDS]) all come from the highest-priority layer setting any of them, so a parent cannot point
 * the root's token at its own `baseUrl` (or the root's `baseUrl` at a parent's password); other fields merge.
 * [root] itself when no layer has `auth:`. [layers] run deepest ancestor first.
 */
internal fun mergedAuthBlocks(
    root: JsonElement,
    layers: List<Pair<String, JsonElement>>,
): JsonElement {
    val kept = (layers.map { it.second } + root).map { layer ->
        JsonObject((layer as? JsonObject).orEmpty().filterKeys { it == ConfigSections.AUTH || it == ConfigSections.ENV })
    }
    if (kept.none { ConfigSections.AUTH in it }) return root
    val owners = kept
        .withIndex()
        .flatMap { (index, layer) ->
            authEntries(layer).filterValues { entry -> entry.keys.any(::isAuthBound) }.keys.map { it to index }
        }.toMap()
    val stripped = kept.mapIndexed { index, layer ->
        val auth = layer[ConfigSections.AUTH] as? JsonObject ?: return@mapIndexed layer
        val entries = auth.mapValues { (name, value) ->
            if (value !is JsonObject || owners[name] == index) value else JsonObject(value.filterKeys { !isAuthBound(it) })
        }
        JsonObject(layer + (ConfigSections.AUTH to JsonObject(entries)))
    }
    return stripped.reduce<JsonElement, JsonElement> { acc, layer -> JsonElementMerger.mergeLayers(acc, layer) }
}

private fun authEntries(layer: JsonObject): Map<String, JsonObject> =
    (layer[ConfigSections.AUTH] as? JsonObject).orEmpty().mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }.toMap()

private fun isAuthBound(key: String): Boolean = ConfigKeyNormalizer.kebabToCamelCase(key) in AUTH_BOUND_FIELDS

/** `AuthConfig` fields that pick where a request goes or what secret it carries: never mixed across layers. */
private val AUTH_BOUND_FIELDS = setOf("type", "baseUrl", "token", "name", "value", "username", "password")
