package io.karandash.bootstrap

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiService
import io.karandash.api.cache.CacheProvider
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.platform.SubprocessBudget
import io.karandash.patterns.PatternResolver
import io.karandash.platform.getCurrentWorkingDirectoryString
import io.karandash.sdk.Logger
import io.karandash.sdk.content.ContentSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Builds [PluginContext]s from the settings of one run: AI service, cache, pattern resolver,
 * subprocess budget, config dir and localhost policy. Each validation run takes its own
 * instance from [PluginBootstrap.newRunContexts], so overlapping runs with different configs
 * never see each other's settings.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class RunPluginContexts internal constructor(
    private val envReader: (String) -> String?,
    private val logger: Logger,
) {
    /** AI service exposed to contexts built by this run. */
    var aiService: AiService? = null

    /** Relaxes SSRF protection for contexts built after it is set. */
    var allowLocalhost: Boolean = false

    /** Resolves named patterns for contexts built after it is set. */
    var patternResolver: PatternResolver? = null

    /** Directory of the run's config file; relative plugin paths resolve against it. */
    var configDir: String = getCurrentWorkingDirectoryString()

    /** The run's cache provider. */
    var cacheProvider: CacheProvider = CacheProvider.NOOP

    /** The run's subprocess budget. */
    var subprocessBudget: SubprocessBudget = SubprocessBudget.UNLIMITED

    /**
     * Backs [PluginContext.resolveContent] for contexts built after it is set; `null` (before
     * config load) makes non-inline sources fail with a clear error.
     */
    var contentResolver: (suspend (ContentSource) -> String)? = null

    /**
     * Creates a context scoped to [moduleId] that exposes [pluginConfig] (a non-object becomes
     * an empty object) and [enrichment], already filtered to the module's declared interest.
     */
    fun contextWithConfig(
        moduleId: String?,
        pluginConfig: JsonElement = JsonObject(emptyMap()),
        enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    ): PluginContext =
        BootstrapPluginContext(
            moduleId = moduleId,
            hostVersion = Version.VERSION,
            workingDirectory = getCurrentWorkingDirectoryString(),
            configDir = configDir,
            ai = aiService,
            allowLocalhost = allowLocalhost,
            cacheProvider = cacheProvider,
            pluginConfig = pluginConfig as? JsonObject ?: JsonObject(emptyMap()),
            envReader = envReader,
            baseLogger = logger,
            patternResolver = patternResolver,
        ).withBudget(subprocessBudget).withEnrichment(enrichment).withContentResolver(contentResolver)

    /** Creates a context scoped to [moduleId] with an empty plugin config. */
    fun forModule(moduleId: String): PluginContext = contextWithConfig(moduleId)
}
