package io.karandash.cli.commands.validate

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.cache.CacheManager
import io.karandash.cache.DownloadsCacheConfig
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.config.GlobLimitsConfig
import io.karandash.config.LimitsConfig
import io.karandash.config.pipeline.BootstrapConfig
import io.karandash.config.pipeline.CacheLayer
import io.karandash.config.pipeline.ChainFetchKeys
import io.karandash.config.pipeline.ConfigChain
import io.karandash.config.pipeline.ConfigChainParser
import io.karandash.config.pipeline.ConfigChainResolver
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.config.pipeline.ConfigParseResult
import io.karandash.config.pipeline.ExtendsEntryResolver
import io.karandash.config.pipeline.RemoteConfigFetcher
import io.karandash.http.NetworkPolicy
import io.karandash.http.SsrfUrlValidator
import io.karandash.interpolation.EnvInterpolation
import io.karandash.manifest.ConfigSource
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ValidateConfigPipeline(
    private val configPathStr: String?,
    private val logger: Logger,
    private val formatRegistry: ConfigFormatRegistry,
    private val cacheManager: CacheManager = CacheManager.defaultInstance(),
    /** Receives each extends-chain entry (source URL, parsed element), deepest ancestor first. */
    private val onConfigLayer: ((url: String, element: JsonElement) -> Unit)? = null,
    /** `--allow-localhost`: lets `extends:` and `{ file: }` fetches reach loopback hosts. */
    private val allowLocalhost: Boolean = false,
    /** Detected CI platform id, exposed to `extends:` `if:` conditions as `platform`. */
    private val platformName: String? = null,
    /** `config validate --base`: a missing local `{ file: }` warns (consumers provide it) instead of failing. */
    private val missingFilesWarn: Boolean = false,
    /** Reads the config file itself (not its `extends:` parents) when set; the language server passes the editor buffer. */
    private val readFile: ((Path) -> String)? = null,
    /** False under `--no-cache`: remote `extends:` configs are fetched directly, never read from or written to the downloads cache. */
    private val cacheDownloads: Boolean = true,
) {
    /**
     * Runs [block] with a fetcher (`--allow-localhost` only) and a downloads cache layer (defaults) for one chain
     * build, and closes the fetcher afterwards. Each `extends:` step applies the chain-fetch keys it inherits
     * ([ChainFetchKeys]) on top of these.
     */
    private suspend fun <T> withFetcher(
        rootElement: JsonElement?,
        block: suspend (RemoteConfigFetcher, CacheLayer) -> T
    ): T {
        // the flag a failed resolution leaves behind
        applyAllowPrivateNetwork(rootElement)
        val fetcher = RemoteConfigFetcher(allowLocalhost = allowLocalhost, logger = logger)
        return try {
            block(
                fetcher,
                CacheLayer(
                    fetcher = fetcher,
                    casCache = cacheManager.getCasCache(CacheManager.DOWNLOADS_NAMESPACE),
                    logger = logger,
                    enabled = cacheDownloads && rootCacheEnabled(rootElement),
                ),
            )
        } finally {
            fetcher.close()
        }
    }

    /** Root `cache.enabled` (any key spelling); true unless it is the boolean false. */
    private fun rootCacheEnabled(rootElement: JsonElement?): Boolean {
        val cache = (rootElement as? JsonObject)?.entries?.firstOrNull { it.key == "cache" }?.value as? JsonObject
        return (cache?.get("enabled") as? JsonPrimitive)?.content != "false"
    }

    /**
     * `limits` of the local root config (`${VAR}`-interpolated by [ChainFetchKeys]), for its own `extends:` entries
     * before the chain is fetched; deeper layers are bounded by [ConfigChainResolver]. Unparseable → defaults, with a warning.
     */
    private fun rootLimits(rootElement: JsonElement?): LimitsConfig = ChainFetchKeys.limits(ChainFetchKeys.of(rootElement), logger)

    /** Resolves the `extends:` chain rooted at [path] (no `{ file: }` references, no merge). */
    private suspend fun resolveChain(
        path: Path,
        fetcher: RemoteConfigFetcher,
        cacheLayer: CacheLayer,
    ): ConfigChain =
        ConfigChainResolver(
            formatRegistry,
            fetcher = fetcher,
            entryResolver = ExtendsEntryResolver.forConfig(
                configDir = path.safeParent ?: Path("."),
                glob = GlobLimitsConfig(),
                formatRegistry = formatRegistry,
                platformName = platformName,
                logger = logger,
            ),
            cacheLayer = cacheLayer,
            logger = logger,
        ).buildChain(ConfigSource(url = path.toString()), rootContent = readFile?.invoke(path))

    /**
     * Resolves and merges the `extends:` chain rooted at [path]. Glob, `@manifest` and conditional
     * entries resolve via [ExtendsEntryResolver.forConfig], bounded by the `limits.glob` each layer inherits.
     * `{ file: }` references are fetched under the chain-fetch keys of the whole chain (`ConfigChain.fetchKeys`).
     * [onChain] receives the resolved chain before it is merged.
     */
    suspend fun mergeChain(
        rootElement: JsonElement,
        path: Path,
        onEntryParsed: ((url: String, element: JsonElement) -> Unit)? = onConfigLayer,
        registry: io.karandash.api.ModuleRegistry? = null,
        onChain: (ConfigChain) -> Unit = {},
    ): JsonElement =
        withFetcher(rootElement) { fetcher, cacheLayer ->
            val chain = resolveChain(path, fetcher, cacheLayer).also(onChain)
            val fetchKeys = chain.fetchKeys
            ChainFetchKeys.allowPrivateNetwork(fetchKeys)?.let { NetworkPolicy.setAllowPrivateNetwork(it) }
            try {
                ConfigChainParser(
                    formatRegistry,
                    fetcher = ChainFetchKeys.fetcherFor(fetcher, fetchKeys, logger),
                    onWarning = { logger.warn(it) },
                    logger = logger,
                    onEntryParsed = onEntryParsed,
                    onMissingFile = if (missingFilesWarn) ::warnConsumerFile else null,
                    extraPreserveKeys = registry?.let(ConfigMapParser::modulePreserveKeys).orEmpty(),
                ).parseAndMerge(chain)
            } finally {
                applyAllowPrivateNetwork(rootElement)
            }
        }.also { merged ->
            // a parent's `allow-private-network` counts like the root's own; [parseMerged] then applies the
            // interpolated value (`${VAR}`)
            applyAllowPrivateNetwork(merged)
        }

    private fun warnConsumerFile(
        reference: String,
        resolved: Path,
    ) = logger.warn("Base config: { file: \"$reference\" } not found ($resolved); each consumer must provide it")

    fun discoverConfig(): Pair<Path?, Boolean> {
        if (configPathStr != null) {
            return Path(configPathStr) to false
        }

        val discovery = ConfigLoader.discover(formatRegistry)
        if (discovery.file != null) {
            return discovery.file to false
        }

        logger.info("No config found, using default rules. Run 'karandash init' to customize.")
        return null to true
    }

    /**
     * Data class holding the bootstrap parse result for two-phase config loading.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `Path?`.
     */
    data class BootstrapParseResult(
        val rawElement: JsonElement,
        val bootstrapConfig: BootstrapConfig,
        val configFilePath: Path?,
        val usingZeroConfig: Boolean
    )

    /**
     * First stage of two-stage config loading: parses the config to a raw map and extracts the bootstrap config.
     *
     * All formats (YAML, JSON, KTS) produce a [JsonElement] with `${VAR}` placeholder strings for
     * env var references. Bootstrap config (extends entries) is extracted from this element.
     * Full config parsing with env var interpolation happens in the second stage after plugins are loaded
     * and the allowed env var set is collected from all registered modules.
     *
     * @return BootstrapParseResult containing raw element and bootstrap config
     */
    suspend fun loadConfigBootstrap(): BootstrapParseResult {
        // a new load starts with the private network closed; mergeChain opens it when the config opts in
        applyAllowPrivateNetwork(null)
        val (configFilePath, usingZeroConfig) = discoverConfig()

        if (usingZeroConfig) {
            return BootstrapParseResult(
                rawElement = JsonObject(emptyMap()),
                bootstrapConfig = BootstrapConfig(extends = emptyList()),
                configFilePath = null,
                usingZeroConfig = true
            )
        }

        // a supplied reader owns the content: an editor buffer need not be saved yet
        if (readFile == null &&
            configPathStr != null &&
            configFilePath != null &&
            (
                !SystemFileSystem.exists(configFilePath) ||
                    SystemFileSystem.metadataOrNull(configFilePath)?.isRegularFile != true
            )
        ) {
            throw ConfigException("${KarandashError.CONFIG_FILE_NOT_FOUND.bracketCode} Config file not found: $configPathStr")
        }

        val rawElement =
            parseToJsonElement(
                requireNotNull(configFilePath) { "Config file path must be set after bootstrap discovery when not using zero config" }
            )
        val bootstrapConfig = ConfigMapParser.parseBootstrapConfig(rawElement, rootLimits(rawElement).extends.effectiveMaxEntries)

        return BootstrapParseResult(
            rawElement = rawElement,
            bootstrapConfig = bootstrapConfig,
            configFilePath = configFilePath,
            usingZeroConfig = false
        )
    }

    /**
     * Parses an already merged (and possibly cleaned) chain element into a [ConfigParseResult], and applies its
     * interpolated `allow-private-network` to the run's [NetworkPolicy] and `security.blocked-hosts` to [SsrfUrlValidator].
     */
    suspend fun parseMerged(
        mergedElement: JsonElement,
        registry: io.karandash.api.ModuleRegistry,
    ): ConfigParseResult {
        val mergedEnvDecl = ConfigMapParser.parseEnvBlock(mergedElement)
        if (mergedEnvDecl.defaults.isNotEmpty()) {
            logger.debug("Env defaults (${mergedEnvDecl.defaults.size}): ${mergedEnvDecl.defaults.keys}")
            for ((key, value) in mergedEnvDecl.defaults) {
                logger.debug("  $key = \"$value\"")
            }
        }
        val aiSubRuleKeys = registry.aiAnalyzers().associate { it.featureKey to it.analyzerId }
        warnUnbracedEnvRefs(mergedElement, mergedEnvDecl.defaults.keys)
        val moduleConfigKeys = registry.allModules().map { it.configKey }.toSet()
        return ConfigMapParser
            .parseFullConfig(
                mergedElement,
                mergedEnvDecl.defaults,
                aiSubRuleKeys,
                moduleConfigKeys,
                logger,
                ConfigMapParser.modulePreserveKeys(registry)
            ).also {
                NetworkPolicy.setAllowPrivateNetwork(it.config.allowPrivateNetwork)
                SsrfUrlValidator.configureAdditionalBlockedHosts(
                    it.config.security.blockedHosts
                        .toSet()
                )
            }
    }

    /**
     * Warns about `$VAR_NAME` references (without braces) that match declared env vars.
     * These are likely authoring errors - `${VAR_NAME}` is the correct syntax.
     */
    private fun warnUnbracedEnvRefs(
        element: JsonElement,
        declaredVars: Set<String>
    ) {
        if (declaredVars.isEmpty()) return
        val warnings = mutableSetOf<String>()
        collectUnbracedRefs(element, declaredVars, warnings)
        for (varName in warnings) {
            logger.warn(
                "Config contains '\$$varName' (without braces) — " +
                    "did you mean '\${$varName}'? Only \${VAR} syntax is interpolated.",
            )
        }
    }

    private fun collectUnbracedRefs(
        element: JsonElement,
        declaredVars: Set<String>,
        warnings: MutableSet<String>,
    ) {
        when (element) {
            is JsonObject -> {
                element.values.forEach { collectUnbracedRefs(it, declaredVars, warnings) }
            }

            is JsonArray -> {
                element.forEach { collectUnbracedRefs(it, declaredVars, warnings) }
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    warnings.addAll(EnvInterpolation.detectUnbracedReferences(element.content, declaredVars))
                }
            }

            else -> {}
        }
    }

    /**
     * Parse a config file to a [JsonElement] (for two-phase loading).
     * Uses the format registry to detect and select the appropriate parser.
     */
    private suspend fun parseToJsonElement(path: Path): JsonElement {
        val content = readFile?.invoke(path) ?: path.readText()
        val formatModule = formatRegistry.getModuleForFile(path.toString())
            ?: throw ConfigException(
                "Unsupported config file format: $path. " +
                    "Supported formats: ${formatRegistry.allExtensions().joinToString(", ") { ".$it" }}"
            )
        return try {
            formatModule.parseToJsonElement(content)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("Failed to parse config file $path: ${e.message}", e)
        }
    }

    internal companion object {
        /**
         * Sets the run's [NetworkPolicy.allowPrivateNetwork] from the `allow-private-network` of [element], `${VAR}`-interpolated
         * by [ChainFetchKeys] (off when absent or not a boolean). Each `extends:` step then fetches under the value it inherits
         * ([ConfigChainResolver]); [mergeChain] applies the merged value and [parseMerged] the interpolated one.
         */
        suspend fun applyAllowPrivateNetwork(element: JsonElement?) =
            NetworkPolicy.setAllowPrivateNetwork(ChainFetchKeys.allowPrivateNetwork(ChainFetchKeys.of(element)) == true)
    }
}
