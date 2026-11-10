package io.karandash.bootstrap

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAwarePluginContext
import io.karandash.api.ai.AiService
import io.karandash.api.cache.CacheProvider
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.exception.ContentResolveException
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.platform.SubprocessBudget
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginCache
import io.karandash.loader.PluginDiscovery
import io.karandash.loader.PluginLoader
import io.karandash.loader.jar.JarPluginLoader
import io.karandash.loader.js.JsPluginLoader
import io.karandash.loader.native.NativePluginLoader
import io.karandash.loader.wasm.WasmPluginLoader
import io.karandash.models.finding.TranslationPack
import io.karandash.models.finding.Translations
import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.PatternResolver
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.safeParent
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Bootstraps plugin loading for the CLI.
 *
 * Loading order:
 *   1a. Built-in modules (always loaded, no manifest.yaml declaration needed) via [BuiltInPluginLoader].
 *   1b. External plugins from `~/.karandash/plugins/` and any additional directories via
 *       [JarPluginLoader], [NativePluginLoader], and [JsPluginLoader].
 *
 * All loaders run in parallel within a [coroutineScope].
 */
class PluginBootstrap(
    registry: ModuleRegistry? = null,
    private val envReader: (String) -> String? = PlatformEnvironment::getEnv,
    val logger: DelegatingLogger = DelegatingLogger(),
    private val onFormatRegistryCreated: (ConfigFormatRegistry) -> Unit = {},
) {
    private val registry: ModuleRegistry = registry ?: ModuleRegistryImpl(logger)
    private val pluginCache = PluginCache()

    private val builtInLoader = BuiltInPluginLoader(this.registry, logger)
    private val jarLoader: PluginLoader = JarPluginLoader(this.registry, pluginCache, logger)
    private val nativeLoader: PluginLoader = NativePluginLoader(this.registry, logger)
    private val jsLoader: PluginLoader = JsPluginLoader(this.registry, logger)
    private val wasmLoader: PluginLoader = WasmPluginLoader(this.registry, logger)

    private val _formatRegistry = MutableStateFlow<ConfigFormatRegistry?>(null)

    /**
     * Load built-in modules and any external plugins from the default directory
     * (`KARANDASH_PLUGIN_DIR`, else `~/.karandash/plugins`) plus [additionalDirs].
     *
     * @param additionalDirs Extra directories to scan beyond the default.
     * @return The combined [LoadResult] from all loaders.
     */
    suspend fun loadPlugins(additionalDirs: List<Path> = emptyList()): LoadResult {
        val dirs = mutableListOf(Path(PluginDiscovery.defaultPluginDirPath(envReader)))
        dirs.addAll(additionalDirs)
        val result = loadFromDirs(dirs.distinct())
        _formatRegistry.value = ConfigFormatRegistry.fromRegistry(registry).also { onFormatRegistryCreated(it) }
        return result
    }

    /**
     * Load built-in modules and external plugins from specific directories only
     * (no default dir).
     */
    suspend fun loadPluginsFromDirs(dirs: List<Path>): LoadResult {
        val result = loadFromDirs(dirs.distinct())
        _formatRegistry.value = ConfigFormatRegistry.fromRegistry(registry).also { onFormatRegistryCreated(it) }
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadFromDirs(pathDirs: List<Path>): LoadResult {
        val collisionsBefore = registry.aliasCollisions().toSet()
        val (result, duration) = measureTimedValue {
            coroutineScope {
                val context = createContext()

                suspend fun safeLoad(
                    loader: PluginLoader,
                    dirs: List<Path>,
                    name: String
                ): LoadResult =
                    try {
                        loader.loadFromDirectories(dirs, context).also { logLoadDecisions(name, it) }
                    } catch (e: Throwable) {
                        // boundary: plugin loader runs third-party code
                        e.rethrowCancellation()
                        logger.debug("$name plugin loader failed: ${e.message}")
                        LoadResult(
                            emptyList(),
                            listOf(LoadResult.LoadFailure(name, e.message ?: "Unknown error")),
                            emptyList(),
                        )
                    }

                val builtInResult = async { safeLoad(builtInLoader, emptyList(), "built-in") }
                val jarResult = async { safeLoad(jarLoader, pathDirs, "jar") }
                val nativeResult = async { safeLoad(nativeLoader, pathDirs, "native") }
                val jsResult = async { safeLoad(jsLoader, pathDirs, "js") }
                val wasmResult = async { safeLoad(wasmLoader, pathDirs, "wasm") }

                LoadResult.merge(awaitAll(builtInResult, jarResult, nativeResult, jsResult, wasmResult))
            }
        }
        logger.debug(
            "Plugin loading: ${duration.inWholeMilliseconds}ms " +
                "(${result.loaded.size} loaded, ${result.failed.size} failed)"
        )
        (registry.aliasCollisions() - collisionsBefore).forEach { logger.warn("$it; the first registered module keeps it") }
        registry.translations().forEach { Translations.register(TranslationPack(it.language, it.pluralRule, it.translations())) }
        return result
    }

    /**
     * One info line per loaded external plugin: id, types, transport, signed (a `.sig` beside the file) and source dir.
     * Built-in modules log their own per-module decisions in [BuiltInPluginLoader].
     */
    private fun logLoadDecisions(
        loader: String,
        result: LoadResult,
    ) {
        if (loader == "built-in") return
        result.loaded.forEach { id ->
            val types = ModuleType.entries.filter { type -> registry.getByType(type).any { it.id == id } }.joinToString(", ")
            val file = result.sources[id]
            val signed = if (file != null && SystemFileSystem.exists(Path("$file.sig"))) "signed" else "unsigned"
            val dir = file?.let { Path(it).safeParent?.toString() } ?: "?"
            logger.debug("Loaded plugin $id ($types) via $loader, $signed, from $dir")
        }
        if (!logger.isDebugEnabled) return
        result.skipped.forEach { logger.debug("[$loader] register module $it: skipped (reason=already-registered or initialize=false)") }
        result.failed.forEach { logger.debug("[$loader] load ${it.path}: failed (reason=${it.reason})") }
    }

    /** Whether [module] was registered by the built-in loader, i.e. is first-party and trusted with secrets. */
    fun isBuiltIn(module: Module): Boolean = builtInLoader.isBuiltIn(module)

    /**
     * Access the underlying plugin cache for introspection.
     */
    fun cache(): PluginCache = pluginCache

    /**
     * Access the module registry.
     */
    fun registry(): ModuleRegistry = registry

    /**
     * Config format registry built from loaded format modules.
     * Available after [loadPlugins] has been called.
     */
    val formatRegistry: ConfigFormatRegistry
        get() = checkNotNull(_formatRegistry.value) {
            "formatRegistry accessed before loadPlugins() was called"
        }

    private val pluginShutdownTimeout = MutableStateFlow(ModuleLifecycleManager.DEFAULT_SHUTDOWN_TIMEOUT)

    /** Sets the per-module bound [shutdown] applies (`timeouts.pluginShutdown`); last loaded config wins. */
    fun configureShutdownTimeout(timeout: Duration) {
        pluginShutdownTimeout.value = timeout
    }

    /**
     * Shut down all loaded plugins in parallel, each module bounded by the configured shutdown timeout.
     */
    suspend fun shutdown() =
        coroutineScope {
            val timeout = pluginShutdownTimeout.value
            listOf(builtInLoader, jarLoader, nativeLoader, jsLoader, wasmLoader)
                .map { async { it.unloadAll(timeout) } }
                .awaitAll()
        }

    /** Settings used by contexts built outside a validation run (plugin loading, CLI commands). */
    private val defaults = RunPluginContexts(envReader, logger)

    /**
     * Fresh context settings for one validation run. Runs set their AI service, cache, pattern
     * resolver and budget there instead of on this shared bootstrap, so overlapping runs with
     * different configs don't interfere.
     */
    fun newRunContexts(): RunPluginContexts = RunPluginContexts(envReader, logger)

    /**
     * Creates a [PluginContext] for a [io.karandash.api.module.CliCommandModule].
     */
    fun contextForModule(module: io.karandash.api.module.CliCommandModule): PluginContext = defaults.forModule(module.id)

    /**
     * Creates a [PluginContext] scoped to the given [moduleId].
     *
     * Used at runtime to create contexts for non-CLI modules (e.g. [io.karandash.api.module.ScriptModule])
     * that do not need an expanded env allowlist beyond the default.
     *
     * @param moduleId The ID of the module the context will be scoped to.
     * @return A [PluginContext] with standard env allowlist.
     */
    fun forModule(moduleId: String): PluginContext = defaults.forModule(moduleId)

    /**
     * [AiService] for contexts created by this bootstrap outside a run. A validation run sets
     * its own on [newRunContexts] instead.
     */
    var aiService: AiService? by defaults::aiService

    /** When true, SSRF protection is relaxed for contexts created by this bootstrap outside a run. */
    var allowLocalhost: Boolean by defaults::allowLocalhost

    /** Pattern resolver for contexts created by this bootstrap outside a run. */
    var patternResolver: PatternResolver? by defaults::patternResolver

    /** Config directory for contexts created by this bootstrap outside a run. */
    var configDir: String by defaults::configDir

    /** Cache provider for contexts created by this bootstrap outside a run. */
    var cacheProvider: CacheProvider by defaults::cacheProvider

    /** Subprocess budget for contexts created by this bootstrap outside a run. */
    var subprocessBudget: SubprocessBudget by defaults::subprocessBudget

    /**
     * Creates a [PluginContext] scoped to the given module, with provider-specific config.
     *
     * @param moduleId The ID of the module the context will be scoped to.
     * @param pluginConfig The provider config block from the parsed config file.
     * @param enrichment Enrichment data available to this module, already filtered to its
     *        declared interest. Defaults to empty.
     * @return A [PluginContext] that exposes [pluginConfig].
     */
    fun contextWithConfig(
        moduleId: String,
        pluginConfig: JsonElement,
        enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    ): PluginContext = defaults.contextWithConfig(moduleId, pluginConfig, enrichment)

    private fun createContext(): PluginContext = defaults.contextWithConfig(moduleId = null)
}

/**
 * CLI-specific implementation of [PluginContext].
 *
 * Environment variable access uses a **denylist** - any env var is permitted
 * unless it appears in [ENV_DENYLIST] (security-sensitive vars like PATH, HOME, SSH keys).
 */
internal class BootstrapPluginContext(
    private val moduleId: String?,
    override val hostVersion: String,
    override val workingDirectory: String,
    override val configDir: String = workingDirectory,
    override val ai: AiService? = null,
    override val allowLocalhost: Boolean = false,
    override val cacheProvider: CacheProvider = CacheProvider.NOOP,
    override val pluginConfig: JsonElement = JsonObject(emptyMap()),
    private val envReader: (String) -> String? = PlatformEnvironment::getEnv,
    private val baseLogger: Logger = CliLogger(),
    private val patternResolver: PatternResolver? = null,
) : AiAwarePluginContext,
    ModuleScopedPluginContextFactory {
    /**
     * Per-run subprocess budget. Set post-construction by [PluginBootstrap] factory methods
     * to avoid exceeding the constructor parameter threshold. Read-once: set immediately
     * after construction, then treated as immutable.
     */
    internal var subprocessBudgetField: SubprocessBudget = SubprocessBudget.UNLIMITED
    override val subprocessBudget: SubprocessBudget get() = subprocessBudgetField

    /**
     * Enrichment data for this module. Set post-construction (same reasoning as
     * [subprocessBudgetField]) to avoid exceeding the constructor parameter threshold.
     * Read-once: set immediately after construction, then treated as immutable.
     */
    internal var enrichmentField: EnrichmentContext = EnrichmentContext.EMPTY
    override val enrichment: EnrichmentContext get() = enrichmentField

    /**
     * The run's content resolver (`ContentResolver` over the config dir, `auth:` block and a strict
     * SSRF fetcher), set post-construction once the config is loaded. `null` before that.
     */
    internal var contentResolverField: (suspend (ContentSource) -> String)? = null

    companion object {
        /**
         * Security denylist of env vars that must never be exposed to plugins.
         * References [io.karandash.interpolation.KarandashEnvSecurity.DENYLIST] - single source of truth.
         */
        val ENV_DENYLIST = io.karandash.interpolation.KarandashEnvSecurity.DENYLIST
    }

    /** Lines a module logs carry `module=<id>` so verbose output says which module decided what. */
    override val logger: Logger = moduleId?.let { baseLogger.withContext("module", it) } ?: baseLogger

    /** Post-construction setter for subprocess budget; returns `this` for fluent use. */
    internal fun withBudget(budget: SubprocessBudget): BootstrapPluginContext {
        subprocessBudgetField = budget
        return this
    }

    /** Post-construction setter for enrichment data; returns `this` for fluent use. */
    internal fun withEnrichment(enrichment: EnrichmentContext): BootstrapPluginContext {
        enrichmentField = enrichment
        return this
    }

    /** Post-construction setter for the content resolver; returns `this` for fluent use. */
    internal fun withContentResolver(resolver: (suspend (ContentSource) -> String)?): BootstrapPluginContext {
        contentResolverField = resolver
        return this
    }

    @ExperimentalKarandashApi
    override suspend fun resolveContent(source: ContentSource): String {
        if (source is ContentSource.Inline) return source.value
        val resolver = contentResolverField
            ?: throw ContentResolveException(
                sourceType = source::class.simpleName ?: "ContentSource",
                reference = source.toString(),
                message = "Content resolution is not available before the config is loaded; " +
                    "call resolveContent from initialize() or later, not during plugin bootstrap.",
            )
        return resolver(source)
    }

    /** Keeps everything but the module id: config, enrichment, budget and content resolver carry over. */
    override fun forModule(moduleId: String): PluginContext =
        BootstrapPluginContext(
            moduleId = moduleId,
            hostVersion = hostVersion,
            workingDirectory = workingDirectory,
            configDir = configDir,
            ai = ai,
            allowLocalhost = allowLocalhost,
            cacheProvider = cacheProvider,
            pluginConfig = pluginConfig,
            envReader = envReader,
            baseLogger = baseLogger,
            patternResolver = patternResolver,
        ).withBudget(subprocessBudgetField).withEnrichment(enrichmentField).withContentResolver(contentResolverField)

    override fun forModule(module: Module): PluginContext = forModule(module.id)

    override fun resolvePattern(pattern: String): Regex {
        val resolver = patternResolver
        if (resolver == null) {
            warn("Pattern resolver not ready yet; matching '$pattern' literally")
            return super.resolvePattern(pattern)
        }
        return when (val result = resolver.resolve(pattern)) {
            is PatternCompilationResult.Success -> {
                result.regex
            }

            is PatternCompilationResult.Error -> {
                warn("Pattern compilation failed for '$pattern': ${result.message}; matching it literally")
                super.resolvePattern(pattern)
            }
        }
    }

    override fun namedPatternNames(): List<String> = patternResolver?.namedPatterns().orEmpty()

    override fun env(name: String): String? {
        if (name in ENV_DENYLIST) {
            warn("Plugin attempted to read restricted environment variable: $name")
            return null
        }
        return envReader(name)
    }

    /**
     * Returns true when the env var is permitted (not in the denylist).
     */
    internal fun isEnvAllowed(name: String): Boolean = name !in ENV_DENYLIST
}
