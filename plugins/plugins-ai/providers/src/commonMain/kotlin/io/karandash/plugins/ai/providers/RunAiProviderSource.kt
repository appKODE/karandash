@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.providers

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiProviderSource
import io.karandash.api.ai.AiServiceKeys
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.run.RunSession
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSerializer
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.crypto.sha256Hex
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.CachedResponse
import io.ktor.client.engine.HttpClientEngine
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Per-run [AiProviderSource] behind `AiService` (analyzers, hooks). Reads the run's published
 * `ai:` config ([AiServiceKeys.AI_CONFIG]) so the main provider gets `ai.apiKey`, `ai.baseUrl`,
 * `ai.timeout` and `ai.maxRetries`, and wraps it with model fallback and the response cache.
 * Registry providers come back as run handles; a `custom` provider is built here and tracked
 * by [session], which shuts it down.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class RunAiProviderSource internal constructor(
    private val session: RunSession,
    private val registry: ModuleRegistry,
    /** Engine of the unregistered `custom` provider; null is the platform default (tests pass a mock). */
    private val customEngine: HttpClientEngine?,
    private val contextFor: (JsonElement) -> PluginContext,
) : AiProviderSource {
    constructor(
        session: RunSession,
        registry: ModuleRegistry,
        contextFor: (JsonElement) -> PluginContext,
    ) : this(session, registry, null, contextFor)

    private class Resolved(
        val base: AiProviderModule?,
        val wrapped: AiProviderModule?,
    )

    private val mutex = Mutex()
    private val memo = atomic(emptyMap<String, Resolved>())

    override suspend fun provider(providerId: String?): AiProviderModule? = resolve(providerId).wrapped

    /**
     * The run handle behind [provider] without the fallback/cache wrappers, for callers that add
     * their own (the main AI rule). The run owns it: [AiProvider.close] on the result is a no-op.
     */
    suspend fun baseProvider(providerId: String?): AiProvider? =
        resolve(providerId).base?.let { base ->
            object : AiProvider by base.asAiProvider() {
                override suspend fun close() = Unit
            }
        }

    private suspend fun resolve(providerId: String?): Resolved =
        mutex.withLock {
            // null and the main provider's own id are one provider: one handle, not a second init of it.
            val normalized = (providerId ?: mainProviderId())?.lowercase()
            val key = normalized.orEmpty()
            memo.value[key]?.let { return@withLock it }
            val resolved = build(normalized)
            memo.update { it + (key to resolved) }
            resolved
        }

    /** `ai.provider` of the published config; null when the config is not an object (any registry provider then). */
    private fun mainProviderId(): String? =
        (session.service(AiServiceKeys.AI_CONFIG) as? JsonObject)?.let { AiPluginConfig.fromPluginConfig(it).provider }

    private suspend fun build(providerId: String?): Resolved {
        val raw = checkNotNull(session.service(AiServiceKeys.AI_CONFIG)) {
            "AI config is not published for this run yet"
        }
        val obj = raw as? JsonObject ?: return firstAvailable(providerId).let { Resolved(it, it) }
        val cfg = AiPluginConfig.fromPluginConfig(obj)
        val id = providerId ?: cfg.provider.lowercase()
        val namedEntry = cfg.providers.entries
            .firstOrNull { it.key.lowercase() == id }
            ?.value
        val namedType = (namedEntry as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
        val base = when {
            namedType != null -> firstAvailable(namedType)
            id == cfg.provider.lowercase() -> mainProvider(id, cfg, obj).also { warnConsensusScope(cfg) }
            else -> firstAvailable(id)
        } ?: return Resolved(null, null)
        return Resolved(base, wrap(base, cfg, obj[KEY_BASE_URL]?.jsonPrimitive?.contentOrNull.orEmpty()))
    }

    /** `consensusModels` alone reaches only `ai.customRules`, not the built-in analyzers. */
    private fun warnConsensusScope(cfg: AiPluginConfig) {
        if (cfg.consensusModels.size < 2 || cfg.analyzerConsensus) return
        contextFor(JsonObject(emptyMap())).logger.warn(
            "[ai] consensusModels is set but analyzerConsensus is false: analyzers (review-comments, " +
                "code-suggestions, ...) call a single model. Set ai.analyzerConsensus: true to run them through consensus.",
        )
    }

    /** Env-only handle of [providerId], or the first registry provider that initializes. */
    private suspend fun firstAvailable(providerId: String?): AiProviderModule? {
        val candidates = if (providerId != null) {
            listOfNotNull(registry.findAiProvider(providerId))
        } else {
            registry.aiProviders()
        }
        return candidates.firstNotNullOfOrNull { session.handle(it, contextFor(JsonObject(emptyMap()))) }
    }

    private suspend fun mainProvider(
        id: String,
        cfg: AiPluginConfig,
        obj: JsonObject,
    ): AiProviderModule? {
        val baseUrl = obj[KEY_BASE_URL]?.jsonPrimitive?.contentOrNull
        val emptyContext = contextFor(JsonObject(emptyMap()))
        val timeout = obj[KEY_TIMEOUT]?.jsonPrimitive?.contentOrNull?.let { parseAiTimeout(it, emptyContext.logger::warn) }
        val maxRetries = obj[KEY_MAX_RETRIES]?.jsonPrimitive?.intOrNull
        val structuredOutput = (obj[KEY_STRUCTURED_OUTPUT] as? JsonPrimitive)?.booleanOrNull
        val registered = registry.findAiProvider(id)
        // only ai.apiKey: the provider falls back to its own env var (resolveApiKey), so the config key wins
        val apiKey = cfg.apiKey
        // An unregistered `custom` provider is a prototype for the same run handle as a registered one, so it gets
        // the whole handle config (reasoningEffort, promptCacheTtl included), not a hand-picked subset.
        val module = registered
            ?: baseUrl?.takeIf { id == PROVIDER_CUSTOM }?.let { CustomAiProvider(baseUrl = it, httpClientEngine = customEngine) }
            ?: return null
        // the provider's own defaults when the config sets none: Anthropic waits 120s per call, OpenAI 60s
        val defaults = module as? AbstractAiProvider
        retriesPastDeadline(
            timeout ?: defaults?.configuredTimeout ?: CustomAiProvider.DEFAULT_TIMEOUT,
            maxRetries ?: defaults?.configuredMaxRetries ?: CustomAiProvider.DEFAULT_RETRIES,
            cfg.executionTimeout,
        )?.let(emptyContext.logger::warn)
        val handleConfig = buildJsonObject {
            apiKey?.let { put("apiKey", it) }
            baseUrl?.let { put("baseUrl", it) }
            timeout?.let {
                put("timeoutSeconds", it.seconds)
                put("timeoutMillis", it.millis)
            }
            maxRetries?.let { put("maxRetries", it) }
            put("promptCacheTtl", cfg.promptCacheTtl)
            cfg.reasoningEffort?.let { put("reasoningEffort", it) }
            structuredOutput?.let { put("structuredOutput", it) }
            obj[KEY_OLLAMA]?.let { put(KEY_OLLAMA, it) }
            if (cfg.stream) {
                put(KEY_STREAM, true)
                put("streamDeadlineMillis", cfg.streamDeadline.millis)
                put("streamStallMillis", cfg.streamStall.millis)
            }
            obj[KEY_STUB]?.let { put(KEY_STUB, it) }
        }
        return session.handle(module, contextFor(handleConfig))
    }

    private fun wrap(
        base: AiProviderModule,
        cfg: AiPluginConfig,
        keyScope: String,
    ): AiProviderModule {
        val logger = contextFor(JsonObject(emptyMap())).logger
        // Consensus is a finding-level vote the rule dispatcher runs per model (ConsensusRun), not a provider wrapper.
        val chain = base.asAiProvider().withResponseChain(cfg, responseCache(), logger, keyScope)
        return object : AiProviderModule by base {
            override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse = chain.completeSdk(request)
        }
    }

    private fun responseCache(): TieredCache<String, AiResponse> {
        val cm = session.service(RunServiceKeys.CACHE_PROVIDER) as? CacheManager
            ?: return TieredCache(
                name = RESPONSE_CACHE_NAME,
                maxEntries = DEFAULT_RESPONSE_CACHE_SIZE,
                tier = CacheTier.MEMORY_ONLY,
            )
        return cm.getOrCreate(
            RESPONSE_CACHE_NAME,
            // Persisted under the cache dir so an identical prompt in the next run is answered without a call.
            CacheSpec(
                maxEntries = cm.memoryMaxEntries(RESPONSE_CACHE_NAME),
                ttl = RESPONSE_CACHE_TTL,
                // --no-cache reads no persisted replies and writes none either
                tier = if (cm.readFiles) CacheTier.MEMORY_AND_FILE else CacheTier.MEMORY_ONLY,
                serializer = ResponseSerializer,
            ),
        )
    }

    /** File-tier codec: [AiResponse] is stored as the serializable [CachedResponse]. */
    private object ResponseSerializer : CacheSerializer<String, AiResponse> {
        // CasCache uses the key as a file name; the raw key carries the base URL (slashes, colons).
        override fun serializeKey(key: String): String = sha256Hex(key)

        override fun serializeValue(value: AiResponse): String =
            KarandashJson.Default.encodeToString(
                CachedResponse.serializer(),
                CachedResponse(
                    content = value.content,
                    promptTokens = value.promptTokens,
                    completionTokens = value.completionTokens,
                    totalTokens = value.totalTokens,
                    model = value.model,
                    createdAt = Clock.System.now().epochSeconds,
                ),
            )

        override fun deserializeValue(data: String): AiResponse {
            val c = KarandashJson.Default.decodeFromString(CachedResponse.serializer(), data)
            return AiResponse(c.content, c.promptTokens, c.completionTokens, c.totalTokens, c.model)
        }
    }

    companion object {
        private const val PROVIDER_CUSTOM = "custom"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_MAX_RETRIES = "maxRetries"
        private const val KEY_STRUCTURED_OUTPUT = "structuredOutput"
        private const val KEY_OLLAMA = "ollama"
        private const val KEY_STREAM = "stream"
        private const val KEY_STUB = "stub"

        /** Name of the AI response cache; `AiRuleProviders` builds its fallback cache under the same name. */
        const val RESPONSE_CACHE_NAME = "responses"

        /** Entries of the response cache when no cache manager sizes it. */
        const val DEFAULT_RESPONSE_CACHE_SIZE = 100
        private val RESPONSE_CACHE_TTL = 30.minutes
    }
}

/**
 * A warning when one call with all its retries can outlast `ai.executionTimeout`: the rule then stops mid-call and
 * keeps only the chunks already done. Null when it fits or no execution timeout is set.
 */
internal fun retriesPastDeadline(
    timeout: KarandashDuration,
    maxRetries: Int,
    executionTimeout: KarandashDuration?,
): String? {
    val total = KarandashDuration.ofMillis(timeout.millis * (maxRetries + 1))
    if (executionTimeout == null || total.millis <= executionTimeout.millis) return null
    return "AI plugin: ai.timeout $timeout x (ai.maxRetries $maxRetries + 1) = $total exceeds ai.executionTimeout $executionTimeout; " +
        "a slow call can stop the rule before its chunks finish"
}
