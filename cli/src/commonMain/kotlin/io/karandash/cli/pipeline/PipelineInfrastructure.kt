package io.karandash.cli.pipeline

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.cache.KarandashCache
import io.karandash.api.module.ExtensionStateStore
import io.karandash.api.module.StatePersistenceAware
import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheTier
import io.karandash.cache.ResponsesCacheConfig
import io.karandash.cache.cas.asStateStore
import io.karandash.cli.cache.connectRemoteCache
import io.karandash.config.Config
import io.karandash.crypto.AwsCredentialChain
import io.karandash.duration.KarandashDuration
import io.karandash.engine.ValidationEngine
import io.karandash.helpers.SafeRegexCompiler
import io.karandash.http.ETagTieredCache
import io.karandash.http.HttpClientSettings
import io.karandash.http.NetworkPolicy
import io.karandash.http.ProviderResponseCache
import io.karandash.http.SsrfUrlValidator
import io.karandash.http.httpResponseCacheSpec
import io.karandash.patterns.NamedPatternExpander
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Infrastructure helpers extracted from [ValidationPipeline] to keep class size
 * within detekt thresholds.
 */
@OptIn(ExperimentalKarandashApi::class)
internal object PipelineInfrastructure {
    /**
     * Null when `cache.enabled` or `cache.state.enabled` is false, so nothing is read or written. With [noCache]
     * (`--no-cache`) the store is written but modules must not reuse cached results.
     */
    fun buildStateStore(
        cacheConfig: CacheConfig,
        cacheManager: CacheManager,
        noCache: Boolean = false,
    ): ExtensionStateStore? {
        if (!cacheConfig.enabled || !cacheConfig.state.enabled) return null
        return cacheManager.getCasCache(CacheManager.STATE_NAMESPACE).asStateStore(cacheConfig.state.ttl, reuseCached = !noCache)
    }

    /**
     * Rule-result cache, persisted under the cache dir for `cache.state.ttl` so a re-run of an unchanged MR
     * reuses it; null when `cache.enabled` is false or under [noCache] (`--no-cache`). `--invalidate-rules` keeps it
     * and recomputes only the named rules ([ValidationEngine]'s `invalidatedRules`).
     */
    fun ruleCache(
        cacheManager: CacheManager?,
        noCache: Boolean,
    ): KarandashCache? =
        cacheManager?.takeIf { it.enabled && !noCache }?.getPersistentStringCache(
            ValidationEngine.RULE_CACHE_NAME,
            cacheManager.memoryMaxEntries(ValidationEngine.RULE_CACHE_NAME),
            cacheManager.cacheConfig.state.ttl
                .toKotlinDuration(),
        )

    /** Parses `--invalidate-rules` (comma-separated rule IDs). */
    fun parseInvalidatedRules(raw: String?): Set<String> =
        raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    /**
     * Wires a remote cache layer onto [cacheManager].
     *
     * Returns the owned scope of the async pushes; the caller [RemoteCachePushScope.drain]s it when the
     * run ends. A failing push is warned, never crashes the run or cancels sibling pushes. Returns null
     * when no remote backend is configured, or when `cache.remote.url` fails [SsrfUrlValidator] (warned;
     * the run continues with the local cache only). [allowLocalhost] (the run's `allow-localhost`) permits a
     * loopback or private backend over HTTP, in the URL check and the HTTP client alike. [configLayers] (the
     * extends chain, root last) tell whether any layer sets `cache.remote.region`. Ambient AWS credentials sign
     * for an AWS host whichever layer set `cache.remote`. [allowPrivateNetwork] (the run's `allow-private-network`)
     * permits a private backend in the URL check and in the async pushes.
     */
    fun wireRemoteCache(
        cacheManager: CacheManager,
        cacheConfig: CacheConfig,
        logger: Logger,
        allowLocalhost: Boolean = false,
        configLayers: List<Pair<String, JsonElement>> = emptyList(),
        allowPrivateNetwork: Boolean = false,
    ): RemoteCachePushScope? {
        val remoteConfig = cacheConfig.remote ?: return null
        if (!cacheConfig.enabled) {
            logger.debug("Remote cache not wired: cache.enabled=false")
            return null
        }
        try {
            SsrfUrlValidator.validate(remoteConfig.url, allowLocalhost, allowPrivateNetwork)
        } catch (e: IllegalArgumentException) {
            logger.warn("Remote cache disabled: cache.remote.url rejected (${e.message})")
            return null
        }
        val connection = connectRemoteCache(remoteConfig, logger, allowLocalhost, AwsCredentialChain(logger), remoteRegionSet(configLayers))
        val backend = connection.backend
        val pushes = RemoteCachePushScope(SupervisorJob(), remoteConfig.pushTimeout.toKotlinDuration(), connection.client)
        val onFailure = CoroutineExceptionHandler { _, e -> logger.warn("Remote cache push failed: ${e.message}") }
        // pushes outlive the calling coroutine: they carry the run's `allow-private-network` themselves
        val pushContext = pushes.job + onFailure + NetworkPolicy(allowPrivateNetwork)
        cacheManager.buildRemoteLayer(backend, remoteConfig, CoroutineScope(pushContext), logger)
        logger.debug("Remote cache enabled: backend=${remoteConfig.backend} pull=${remoteConfig.pull} push=${remoteConfig.push}")
        return pushes
    }

    /** True when any config layer sets `cache.remote.region`; otherwise S3 takes the AWS chain's region. */
    fun remoteRegionSet(configLayers: List<Pair<String, JsonElement>>): Boolean =
        configLayers.any { (_, element) -> remoteSection(element)?.containsKey(REGION_KEY) == true }

    private fun remoteSection(element: JsonElement): JsonObject? =
        ((element as? JsonObject)?.get(CACHE_KEY) as? JsonObject)?.get(REMOTE_KEY) as? JsonObject

    private const val CACHE_KEY = "cache"
    private const val REMOTE_KEY = "remote"
    private const val REGION_KEY = "region"

    /** Overrides `timeouts.http` for the run: bare seconds (`60`) or a duration (`90s`, `2m`). */
    const val HTTP_TIMEOUT_ENV = "KARANDASH_HTTP_TIMEOUT"

    /**
     * Run-wide HTTP client settings from `timeouts.http` and the `http:` block; unset keys stay null.
     * [HTTP_TIMEOUT_ENV] wins over `timeouts.http`; an invalid value is ignored with a warning.
     */
    fun httpClientSettings(
        config: Config,
        logger: Logger? = null,
        env: (String) -> String? = PlatformEnvironment::getEnv,
    ): HttpClientSettings =
        HttpClientSettings(
            timeout = httpTimeoutFromEnv(env, logger) ?: config.timeouts?.http,
            retry = config.http.retry?.let { HttpClientSettings.Retry(it.effectiveMaxAttempts, it.baseDelay) },
            maxPages = config.http.pagination?.effectiveMaxPages,
        )

    private fun httpTimeoutFromEnv(
        env: (String) -> String?,
        logger: Logger?,
    ): KarandashDuration? {
        val raw = env(HTTP_TIMEOUT_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = raw.toLongOrNull()?.seconds ?: Duration.parseOrNull(raw)
        if (parsed == null || !parsed.isPositive()) {
            logger?.warn("Ignoring $HTTP_TIMEOUT_ENV='$raw' (expected seconds or a duration such as 90s or 2m)")
            return null
        }
        logger?.debug("HTTP timeout is $parsed ($HTTP_TIMEOUT_ENV)")
        return KarandashDuration.ofMillis(parsed.inWholeMilliseconds)
    }

    private val compileLimitsLock = Mutex()

    /** Each active run's regex limits and pattern depth; only changed under [compileLimitsLock]. */
    private val activeCompileLimits = MutableStateFlow<List<Pair<SafeRegexCompiler.Limits, Int>>>(emptyList())
    private val appliedRegexLimits = MutableStateFlow<SafeRegexCompiler.Limits?>(null)

    /**
     * Installs `limits.regex` and `limits.patterns` (clamped) for the process-wide regex compiler and
     * pattern expander, which plugins reach without run services. Overlapping runs get the strictest
     * values of all active runs, so no run is looser than its own config; the returned undo (for the
     * run's `finally`) re-applies the remaining runs' values, or the built-ins when none is left.
     */
    suspend fun installCompileLimits(config: Config): suspend () -> Unit {
        val entry = SafeRegexCompiler.Limits(
            maxPatternLength = config.limits.regex.effectiveMaxPatternLength,
            maxQuantifierNesting = config.limits.regex.effectiveMaxQuantifierNesting,
        ) to config.limits.patterns.effectiveMaxExpansionDepth
        compileLimitsLock.withLock {
            activeCompileLimits.update { it + entry }
            applyStrictestCompileLimits()
        }
        return {
            withContext(NonCancellable) {
                compileLimitsLock.withLock {
                    activeCompileLimits.update { it - entry }
                    applyStrictestCompileLimits()
                }
            }
        }
    }

    private fun applyStrictestCompileLimits() {
        val active = activeCompileLimits.value
        appliedRegexLimits.value?.let(SafeRegexCompiler::clearLimits)
        if (active.isEmpty()) {
            appliedRegexLimits.value = null
            NamedPatternExpander.clearMaxDepth(NamedPatternExpander.maxDepth)
            return
        }
        val strictest = SafeRegexCompiler.Limits(
            maxPatternLength = active.minOf { it.first.maxPatternLength },
            maxQuantifierNesting = active.minOf { it.first.maxQuantifierNesting },
        )
        SafeRegexCompiler.installLimits(strictest)
        appliedRegexLimits.value = strictest
        NamedPatternExpander.installMaxDepth(active.minOf { it.second })
    }

    /**
     * The run's provider response cache, or null when `cache.responses.enabled` is false.
     * TTL: `cache.responses.ttl`, else `timeouts.cacheTtl`, else `cache.defaultTtl`, else [ResponsesCacheConfig.DEFAULT_TTL].
     * Memory entries: `http.responseCache.maxEntries`, else the `cache.memory` budget for "responses".
     */
    fun buildResponseCache(
        cacheManager: CacheManager,
        cacheConfig: CacheConfig,
        config: Config,
    ): ProviderResponseCache? {
        val responses = cacheConfig.responses
        if (!responses.enabled) return null
        val ttl = responseTtl(cacheConfig, config).toKotlinDuration()
        val maxEntries = config.http.responseCache?.maxEntries ?: cacheManager.memoryMaxEntries("responses")
        val tieredCache = cacheManager.getOrCreate(
            name = ProviderResponseCache.CACHE_NAME,
            spec = httpResponseCacheSpec(
                maxEntries = maxEntries,
                tier = if (responses.persist) CacheTier.MEMORY_AND_FILE else CacheTier.MEMORY_ONLY,
            ),
        )
        return ProviderResponseCache(cache = ETagTieredCache(cache = tieredCache, defaultTtl = ttl), defaultTtl = ttl)
    }

    internal fun responseTtl(
        cacheConfig: CacheConfig,
        config: Config,
    ): KarandashDuration =
        cacheConfig.responses.ttl ?: config.timeouts?.cacheTtl ?: cacheConfig.defaultTtl ?: ResponsesCacheConfig.DEFAULT_TTL

    fun runCacheGc(
        cacheManager: CacheManager?,
        logger: Logger,
    ) {
        val result = cacheManager?.runGc() ?: return
        if (result.expiredIndexesRemoved > 0 || result.orphanBlobsRemoved > 0) {
            logger.debug(
                "Cache GC: removed ${result.expiredIndexesRemoved} expired, " +
                    "${result.orphanBlobsRemoved} orphans, reclaimed ${result.bytesReclaimed} bytes"
            )
        }
    }

    fun injectStateStore(
        store: ExtensionStateStore?,
        registry: io.karandash.api.ModuleRegistry,
        extensions: List<io.karandash.api.module.PipelineExtension>,
    ) {
        (registry.ruleModules() + extensions)
            .filterIsInstance<StatePersistenceAware>()
            .forEach { it.configureStateStore(store) }
    }
}

/**
 * Owned scope of the remote cache's async pushes (SS-3). [drain] stops accepting new pushes, waits for
 * the in-flight ones at most [drainTimeout] (each push is already bounded by `cache.remote.pushTimeout`),
 * cancels whatever is left, then closes the remote [client].
 */
internal class RemoteCachePushScope(
    val job: CompletableJob,
    private val drainTimeout: Duration,
    private val client: HttpClient,
) {
    suspend fun drain() {
        job.complete()
        if (withTimeoutOrNull(drainTimeout) { job.join() } == null) job.cancel()
        client.close()
    }
}
