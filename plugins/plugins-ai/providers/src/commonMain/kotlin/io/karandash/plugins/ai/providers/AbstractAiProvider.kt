@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.providers

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.module.ModuleScope
import io.karandash.api.run.RunScopedServices
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpClientSettings
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.RetryBackoff
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrl
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Run-handle config decoded from [PluginContext.pluginConfig] in [AbstractAiProvider.newHandle].
 * Unset fields keep the prototype's values.
 */
@Serializable
internal data class ProviderHandleConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val timeoutSeconds: Long? = null,
    /** Exact timeout; wins over [timeoutSeconds], which truncates a sub-second value to 0. */
    val timeoutMillis: Long? = null,
    val maxRetries: Int? = null,
    /** `ai.promptCacheTtl`; only providers with prompt-cache breakpoints (Anthropic) read it. */
    val promptCacheTtl: String? = null,
    /** `ai.reasoningEffort`; only OpenAI-compatible providers read it. */
    val reasoningEffort: String? = null,
    /** `ai.structuredOutput` as written; null when unset (only api.openai.com then gets a strict schema; other hosts get a plain request). */
    val structuredOutput: Boolean? = null,
    /** `ai.stream`: request a streamed reply, so `ai.timeout` bounds the silence between chunks, not the whole call. */
    val stream: Boolean? = null,
    /** `ai.streamDeadline`: total time a streamed call may take before it is retried. */
    val streamDeadlineMillis: Long? = null,
    /** `ai.streamStall`: silence allowed between two chunks of a streamed call. */
    val streamStallMillis: Long? = null,
)

/**
 * `ai.timeout`: a duration string (`8m`, `1500ms`) or a bare number of seconds (as `GuestCallTimeout`).
 * A bare number of 1000 or more is still seconds but almost certainly a milliseconds leftover, so it is [warn]ed.
 */
internal fun parseAiTimeout(
    raw: String,
    warn: (String) -> Unit,
): KarandashDuration {
    val number = raw.trim().toLongOrNull() ?: return KarandashDuration.parse(raw)
    if (number >= SUSPECT_MILLIS_TIMEOUT_FROM) {
        warn(
            "ai.timeout: $number is read as seconds (${KarandashDuration.ofSeconds(number)}); " +
                "write \"${number}ms\" if you meant milliseconds",
        )
    }
    return KarandashDuration.ofSeconds(number)
}

private const val SUSPECT_MILLIS_TIMEOUT_FROM = 1000L

/**
 * Runtime context holder (carries a lambda, a live [Logger] and run services); not serializable by design.
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `RunScopedServices?`.
 */
private data class ProviderRuntime(
    val logger: Logger = NoOpLogger,
    val services: RunScopedServices? = null,
    val envLookup: (String) -> String? = { null },
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val promptCacheTtl: String? = null,
    val reasoningEffort: String? = null,
    val structuredOutput: Boolean? = null,
    val isHandle: Boolean = false,
    /** [PluginContext.allowLocalhost]: the run's `allow_localhost`, so a self-hosted model on loopback is reachable. */
    val allowLocalhost: Boolean = false,
    val stream: Boolean = false,
    val streamDeadline: KarandashDuration = KarandashDuration.ofMinutes(DEFAULT_STREAM_DEADLINE_MINUTES),
    val streamStall: KarandashDuration = KarandashDuration.ofSeconds(DEFAULT_STREAM_STALL_SECONDS),
)

private const val DEFAULT_STREAM_DEADLINE_MINUTES = 5L
private const val DEFAULT_STREAM_STALL_SECONDS = 60L
private const val KEY_STREAM_DEADLINE = "ai.streamDeadline"
private const val KEY_STREAM_STALL = "ai.streamStall"

/** A 2xx reply: the buffered body, or the decoded [chunks] of a streamed one. */
@Serializable
private data class ProviderResult(
    val bodyText: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val chunks: List<AiChunk>? = null,
)

/**
 * Base class for AI providers that share retry logic, HTTP client setup, and
 * status-code handling. Subclasses implement only the provider-specific parts:
 *
 * - [sendRequest] -- build and send the HTTP request (endpoint, auth, body)
 * - [parseSuccessResponse] -- deserialize a 2xx response body into [AiResponse]
 * - [handleErrorStatus] -- map non-2xx status codes to [AiProviderException]
 *
 * @param timeout Request timeout
 * @param maxRetries Retries of a transient error after the first attempt (0: one attempt)
 * @param httpClientEngine Optional engine override (for testing with MockEngine)
 */
abstract class AbstractAiProvider(
    protected val timeout: KarandashDuration,
    protected val maxRetries: Int,
    private val httpClientEngine: HttpClientEngine?,
) : AiProvider,
    AiProviderModule {
    protected companion object {
        protected const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        protected const val HTTP_NOT_FOUND = 404
        protected const val HTTP_RATE_LIMITED = 429

        /** Base of the retry backoff ([RetryBackoff]): 1s, 2s, 4s, ... */
        private const val RETRY_BASE_DELAY_MS = 1_000L

        /** The client never retries: [executeWithRetry] owns the AI retry, so one failure is not retried twice over. */
        private val NO_CLIENT_RETRY = HttpClientSettings.Retry(maxRetries = 0, baseDelay = KarandashDuration.ofSeconds(1))

        /** Silence tolerated on a non-streaming call; OpenRouter keep-alive bytes reset it, a silent connection does not. */
        private val STALL_LIMIT = KarandashDuration.ofSeconds(180)

        /** A dead host fails within this, not the whole `ai.timeout`; a reachable one connects far sooner. */
        private val CONNECT_TIMEOUT_CAP = KarandashDuration.ofSeconds(15)
        protected const val HTTP_SERVER_ERROR_MIN = 500
        protected const val HTTP_SERVER_ERROR_MAX = 599
        protected const val HTTP_SUCCESS_MIN = 200
        protected const val HTTP_SUCCESS_MAX = 299
    }
    // -- AiProviderModule (SDK) lifecycle --

    override val version: String = "1.7.0"

    /** One handle per resolved config per run; the run session initializes and shuts it down. */
    override val scope: ModuleScope get() = ModuleScope.PER_RUN

    /** [completeRequest] flattens the blocks itself unless a provider sends them natively (Anthropic). */
    override val supportsContextBlocks: Boolean get() = true

    /**
     * State written by [configure] (legacy prototype path) and [initialize]. Folded into one
     * [MutableStateFlow] so no field is a `var`; a run handle gets its config once in [newHandle].
     */
    private val runtime = MutableStateFlow(ProviderRuntime())

    private val configureOnHandleWarned = atomic(false)

    protected val logger: Logger get() = runtime.value.logger

    /**
     * Env-var lookup function set during [initialize].
     * Delegates to [PluginContext.env] without storing the context itself.
     */
    protected val envLookup: (String) -> String? get() = runtime.value.envLookup

    /** API key from config: set by the resolver ([configure]) or the run handle config. */
    protected val configApiKey: String? get() = runtime.value.apiKey

    /** API key given to the constructor; null for a provider that takes none. */
    protected open val constructorApiKey: String? get() = null

    /** One order for every provider: constructor key, `ai.apiKey`, then its own [apiKeyEnvVar] only. */
    protected fun resolveApiKey(): String? =
        constructorApiKey?.takeIf { it.isNotEmpty() }
            ?: configApiKey?.takeIf { it.isNotEmpty() }
            ?: apiKeyEnvVar?.let(envLookup)

    /** Base URL from config: set by the resolver ([configure]) or the run handle config. */
    protected val configBaseUrl: String? get() = runtime.value.baseUrl

    /** `ai.promptCacheTtl` from the run handle config or [configure]; null when unset. */
    protected val configPromptCacheTtl: String? get() = runtime.value.promptCacheTtl

    /** `ai.reasoningEffort` from the run handle config or [configure]; null when unset. */
    protected val configReasoningEffort: String? get() = runtime.value.reasoningEffort

    /** `ai.structuredOutput` as written in config, null when unset: set by [configure] or the run handle config. */
    protected val configStructuredOutput: Boolean? get() = runtime.value.structuredOutput

    /** `ai.stream`: the request asks for a streamed reply, which [decodeStreamEvent] reads chunk by chunk. */
    protected val streaming: Boolean get() = runtime.value.stream

    /** Appended to a timeout message: without streaming `ai.timeout` bounds the whole call, so a stalled one waits it out. */
    private val streamHint: String
        get() = if (streaming) "" else " (set ai.stream: true to bound only the silence between chunks, so a stalled call fails fast)"

    /** Endpoint when neither `ai.baseUrl` nor [baseUrlOverrideEnvVar] is set. */
    protected abstract val defaultBaseUrl: String

    /** Env var that replaces [defaultBaseUrl]; [baseUrlEnvVar] unless the provider names an optional one. */
    protected open val baseUrlOverrideEnvVar: String? get() = baseUrlEnvVar

    /** One order for every provider: `ai.baseUrl`, then [baseUrlOverrideEnvVar], then [defaultBaseUrl]. */
    protected open fun resolveBaseUrl(): String =
        configBaseUrl?.takeIf { it.isNotEmpty() }
            ?: baseUrlOverrideEnvVar?.let(envLookup)?.takeIf { it.isNotEmpty() }
            ?: defaultBaseUrl

    /**
     * Injects config-resolved values before [initialize] is called.
     * Called by the resolver to pass config-level apiKey, baseUrl,
     * timeout and retry count that the provider's [resolveApiKey]/[resolveBaseUrl] can use.
     * When [timeout] differs from the constructor default, the HTTP client is recreated.
     * On a run handle ([newHandle]) this is a no-op: its config is fixed at creation.
     */
    suspend fun configure(
        apiKey: String?,
        baseUrl: String?,
        timeout: KarandashDuration? = null,
        maxRetries: Int? = null,
        structuredOutput: Boolean? = null,
        stream: Boolean? = null,
        streamDeadline: KarandashDuration? = null,
        streamStall: KarandashDuration? = null,
        promptCacheTtl: String? = null,
        reasoningEffort: String? = null,
    ) {
        if (runtime.value.isHandle) {
            if (configureOnHandleWarned.compareAndSet(expect = false, update = true)) {
                logger.warn("AI provider '$providerId': configure() on a run handle is ignored")
            }
            return
        }
        runtime.update {
            it.copy(
                apiKey = apiKey ?: it.apiKey,
                baseUrl = baseUrl ?: it.baseUrl,
                structuredOutput =
                    structuredOutput ?: it.structuredOutput,
                stream = stream ?: it.stream,
                streamDeadline = streamDeadline ?: it.streamDeadline,
                streamStall = streamStall ?: it.streamStall,
                promptCacheTtl = promptCacheTtl ?: it.promptCacheTtl,
                reasoningEffort = reasoningEffort ?: it.reasoningEffort,
            )
        }
        maxRetries?.let { effectiveMaxRetries.value = it }
        // the client's timeouts depend on streaming (idle-only), so a change rebuilds it
        if ((timeout != null && timeout != effectiveTimeout.value) || stream != null || streamStall != null) {
            timeout?.let { effectiveTimeout.value = it }
            cachedHttpClient.getAndUpdate { null }?.close()
        }
    }

    override suspend fun initialize(context: PluginContext): Boolean {
        val services = RunScopedServices.current()
        val previous = runtime.getAndUpdate {
            it.copy(logger = context.logger, envLookup = context::env, services = services, allowLocalhost = context.allowLocalhost)
        }
        if (previous.allowLocalhost != context.allowLocalhost) cachedHttpClient.getAndUpdate { null }?.close()
        // a config that does not decode (maxRetries: "x") is a bad config: false, the same for every provider
        val config = context.pluginConfig as? JsonObject
        if (config != null && decodeHandleConfig(config) == null) {
            logger.warn("AI provider '$providerId': invalid config, provider disabled")
            return false
        }
        return onInitialize(context)
    }

    /** Null when [config] does not decode; the handle then starts on defaults and its [initialize] reports it. */
    private fun decodeHandleConfig(config: JsonObject): ProviderHandleConfig? =
        try {
            KarandashJson.Default.decodeFromJsonElement(ProviderHandleConfig.serializer(), config)
        } catch (e: IllegalArgumentException) {
            logger.debug("AI provider '$providerId': config does not decode: ${e.message}")
            null
        }

    /**
     * Returns a new, not yet initialized provider of the same class whose apiKey, baseUrl,
     * timeout and retries come from [PluginContext.pluginConfig] ([ProviderHandleConfig]);
     * unset fields keep this prototype's values.
     */
    override suspend fun newHandle(context: PluginContext): Module {
        val cfg = (context.pluginConfig as? JsonObject)
            ?.let { decodeHandleConfig(it) }
            ?: ProviderHandleConfig()
        val proto = runtime.value
        val handle = spawn(
            timeout = cfg.timeoutMillis?.let { KarandashDuration.ofMillis(it) }
                ?: cfg.timeoutSeconds?.let { KarandashDuration.ofSeconds(it) }
                ?: effectiveTimeout.value,
            maxRetries = cfg.maxRetries ?: effectiveMaxRetries.value,
            engine = httpClientEngine,
        )
        // a subclass that does not override spawn() gets its parent's class back: keep the
        // pre-handle behaviour (this instance, shut down by the run) rather than a wrong type
        if (handle::class != this::class) return this
        handle.runtime.value = ProviderRuntime(
            apiKey = cfg.apiKey ?: proto.apiKey,
            baseUrl = cfg.baseUrl ?: proto.baseUrl,
            promptCacheTtl = cfg.promptCacheTtl,
            reasoningEffort = cfg.reasoningEffort,
            structuredOutput = cfg.structuredOutput ?: proto.structuredOutput,
            stream = cfg.stream ?: proto.stream,
            streamDeadline = cfg.streamDeadlineMillis?.let { KarandashDuration.ofMillis(it) } ?: proto.streamDeadline,
            streamStall = cfg.streamStallMillis?.let { KarandashDuration.ofMillis(it) } ?: proto.streamStall,
            isHandle = true,
        )
        return handle
    }

    /** Creates a fresh instance of this provider class, keeping this instance's constructor values. */
    protected abstract fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider

    /** Initialization check called by [initialize] (context not stored); by default a required API key must resolve. */
    protected open suspend fun onInitialize(context: PluginContext): Boolean = !requiresApiKey || resolveApiKey() != null

    override suspend fun shutdown() {
        close()
    }

    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse = completeSdk(request)

    // -- Shared infrastructure --

    protected val json: Json = KarandashJson.Lenient

    private val cachedHttpClient = MutableStateFlow<HttpClient?>(null)

    /** Timeout used when (re)building the client; [configure] may override the constructor value. */
    private val effectiveTimeout = MutableStateFlow(timeout)

    /** Retries after the first attempt for [executeWithRetry]; [configure] may override the constructor value. */
    private val effectiveMaxRetries = MutableStateFlow(maxRetries)

    /** `ai.timeout` this provider runs with when the config sets none (Anthropic 120s, OpenAI 60s, ...). */
    internal val configuredTimeout: KarandashDuration get() = effectiveTimeout.value

    /** `ai.maxRetries` this provider runs with when the config sets none. */
    internal val configuredMaxRetries: Int get() = effectiveMaxRetries.value

    /**
     * True for a provider whose server is local by design (Ollama): its client reaches loopback and private hosts
     * whatever the run's `allow_localhost`.
     */
    protected open val permitsLocalHosts: Boolean = false

    internal val httpClient: HttpClient
        get() = acquireClient()

    /** Lock-free lazy creation: a caller that loses the publish race closes its own client. */
    private fun acquireClient(): HttpClient {
        while (true) {
            cachedHttpClient.value?.let { return it }
            val timeout = effectiveTimeout.value
            val created = ProviderHttpClientFactory.create(
                engine = httpClientEngine,
                timeout = timeout,
                connectTimeout = minOf(timeout, CONNECT_TIMEOUT_CAP),
                json = json,
                services = runtime.value.services,
                logger = logger,
                allowLocalhost = runtime.value.allowLocalhost || permitsLocalHosts,
                idleTimeoutOnly = streaming,
                socketTimeout = stallLimit(timeout),
                retry = NO_CLIENT_RETRY,
            )
            if (cachedHttpClient.compareAndSet(null, created)) return created
            created.close()
        }
    }

    /**
     * Stall protection: a connection that sends nothing for [STALL_LIMIT] (`ai.streamStall` when streaming) fails and is
     * retried instead of holding the run for a long `ai.timeout`. Null (no cap) when `ai.timeout` is already within it.
     */
    private fun stallLimit(timeout: KarandashDuration): KarandashDuration? =
        (if (streaming) runtime.value.streamStall else STALL_LIMIT).takeIf { timeout.millis > it.millis }

    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): AiResponse = timedCall(model) { sendRequest(prompt, systemPrompt, model, maxTokens, temperature, jsonMode) }

    /** Routes a request carrying a [AiCompletionRequest.responseSchema] to [sendStructuredRequest], others to [sendCompletion]. */
    override suspend fun completeRequest(request: AiCompletionRequest): AiResponse =
        timedCall(request.model) { if (request.responseSchema == null) sendCompletion(request) else sendStructuredRequest(request) }

    private suspend fun timedCall(
        model: String,
        send: suspend () -> HttpResponse,
    ): AiResponse {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val result = executeWithRetry(model, send)
        val durationMs = Clock.System.now().toEpochMilliseconds() - startTime
        val response = result.chunks?.let { mergeChunks(it, model, durationMs) }
            ?: parseSuccessResponse(result.bodyText, result.statusCode, durationMs, result.headers)
        result.chunks
            ?.mapNotNull { it.refusal }
            ?.joinToString("")
            ?.takeIf { it.isNotEmpty() }
            ?.let { warnRefusal(response.model, it) }
        if (result.chunks?.any { it.truncated } == true) {
            warnTruncated(response.model, response.completionTokens, noContent = response.content.isBlank())
        }
        val cache = if (response.cacheReadTokens + response.cacheWriteTokens > 0) {
            " cache=read:${response.cacheReadTokens}/write:${response.cacheWriteTokens}"
        } else {
            ""
        }
        logger.info(
            "AI call: provider=$providerId model=${response.model} " +
                "tokens=${response.promptTokens}+${response.completionTokens}=${response.totalTokens}$cache latency=${durationMs}ms",
        )
        return response
    }

    /**
     * Send a request without a schema. The default sends [AiCompletionRequest.contextBlocks] inside the prompt;
     * a provider that sends them as separate blocks overrides this.
     */
    protected open suspend fun sendCompletion(request: AiCompletionRequest): HttpResponse {
        val flat = request.withContextInPrompt()
        return sendRequest(flat.prompt, flat.systemPrompt, flat.model, flat.maxTokens, flat.temperature, flat.jsonMode)
    }

    /**
     * Send a request whose output must follow [AiCompletionRequest.responseSchema]. Providers with native
     * schema-constrained decoding override this; the default falls back to plain JSON mode, and the
     * caller parses the text against its schema.
     */
    protected open suspend fun sendStructuredRequest(request: AiCompletionRequest): HttpResponse =
        sendRequest(
            request.withContextInPrompt().prompt,
            request.systemPrompt,
            request.model,
            request.maxTokens,
            request.temperature,
            jsonMode = true
        )

    /**
     * Execute the provider-specific HTTP request.
     *
     * Implementations must build the request body, set auth headers, and POST to
     * the correct endpoint. Must propagate [HttpRequestTimeoutException] directly
     * and wrap other I/O failures in [AiProviderException].
     *
     * @return The raw [HttpResponse] from the provider
     */
    protected abstract suspend fun sendRequest(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): HttpResponse

    /**
     * Parse a successful (2xx) response body into an [AiResponse].
     *
     * @param bodyText The raw response body text
     * @param statusCode The HTTP status code (always in 200..299)
     * @param durationMs Elapsed time for the overall request including retries
     * @param headers Response headers (lowercase keys, first value per header)
     * @return Parsed [AiResponse] with content and usage metadata
     * @throws AiProviderException if the response body cannot be parsed
     */
    protected abstract fun parseSuccessResponse(
        bodyText: String,
        statusCode: Int,
        durationMs: Long,
        headers: Map<String, String>,
    ): AiResponse

    /**
     * Map a non-2xx HTTP status code to an [AiProviderException].
     *
     * Subclasses can handle provider-specific codes (e.g. Anthropic 529, Ollama 404)
     * and fall back to [defaultHandleErrorStatus] for common codes.
     *
     * @param statusCode The HTTP status code
     * @param bodyText The raw response body
     * @return An [AiProviderException] describing the error
     */
    protected abstract fun handleErrorStatus(
        statusCode: Int,
        bodyText: String,
    ): AiProviderException

    /**
     * Default error-status handling for codes shared across providers.
     * Subclasses call this from [handleErrorStatus] for unhandled codes.
     */
    protected fun defaultHandleErrorStatus(
        statusCode: Int,
        bodyText: String,
    ): AiProviderException =
        when (statusCode) {
            HTTP_UNAUTHORIZED -> AiProviderException(
                message = "Authentication failed: invalid or missing API key",
                statusCode = HTTP_UNAUTHORIZED,
                retryable = false,
            )

            HTTP_FORBIDDEN -> AiProviderException(
                message = "Permission denied: the API key has no access to this resource",
                statusCode = HTTP_FORBIDDEN,
                retryable = false,
            )

            HTTP_RATE_LIMITED -> AiProviderException(
                message = "Rate limited by API provider",
                statusCode = HTTP_RATE_LIMITED,
                retryable = true,
            )

            in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> AiProviderException(
                message = "AI provider server error: $statusCode",
                statusCode = statusCode,
                retryable = true,
            )

            else -> AiProviderException(
                message = "Unexpected status $statusCode: $bodyText",
                statusCode = statusCode,
                retryable = false,
            )
        }

    /**
     * One `data:` payload of a streamed reply (`ai.stream: true`) as a chunk, null for an event that carries
     * neither text nor usage. Throws [AiProviderException] for an error event.
     */
    protected abstract fun decodeStreamEvent(data: String): AiChunk?

    /** With `ai.stream: true`, the provider's deltas as they arrive (one attempt, no retry); otherwise one chunk. */
    override fun streamCompletion(request: AiCompletionRequest): Flow<AiChunk> =
        if (!streaming) {
            super.streamCompletion(request)
        } else {
            flow {
                val response = if (request.responseSchema == null) sendCompletion(request) else sendStructuredRequest(request)
                if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                    throw errorOf(response, request.model, effectiveTimeout.value.millis.milliseconds)
                }
                emitAll(readStream(response))
            }
        }

    /** a reply cut at max_tokens is usually unparseable JSON; say why instead of failing silently later. */
    protected fun warnTruncated(
        model: String,
        completionTokens: Int,
        noContent: Boolean = false,
    ) {
        if (noContent) {
            logger.warn(
                "[ai] $providerId model=$model returned no content: all $completionTokens output tokens went to " +
                    "hidden reasoning before the answer started. Set ai.reasoningEffort: low, use a non-reasoning model for this rule " +
                    "or raise its token cap (ai.<analyzer-id>.max-tokens)",
            )
            return
        }
        logger.warn(
            "[ai] $providerId model=$model stopped at the token limit " +
                "($completionTokens output tokens): the response is truncated, " +
                "raise the analyzer cap (ai.<analyzer-id>.max-tokens)",
        )
    }

    /** The model declined the request (OpenAI `message.refusal`): the reply has no content, so say why. */
    protected fun warnRefusal(
        model: String,
        refusal: String,
    ) {
        logger.warn("[ai] $providerId model=$model refused the request: $refusal")
    }

    // limit: a `post` response body is read under the idle timeout (the point of `ai.stream`) but buffered before the
    // first chunk is emitted; per-chunk delivery needs the providers to send via `prepareRequest().execute {}`
    private fun readStream(response: HttpResponse): Flow<AiChunk> =
        flow { emitAll(sseData(response.bodyAsChannel())) }
            .catch { e ->
                if (e is SocketTimeoutException) throw streamStalled(e)
                if (e !is IOException) throw e
                throw AiProviderException("AI provider '$providerId': stream stopped: ${e.message}", retryable = true, cause = e)
            }.mapNotNull { decodeStreamEvent(it) }

    override suspend fun close() {
        cachedHttpClient.getAndUpdate { null }?.close()
        unreachable.value = emptyMap()
    }

    /**
     * Base URL to the `unreachable: <host> (<kind>)` reason of its first connect failure. A run handle lives for one
     * run and every rule shares it, so later calls fail at once instead of each waiting out the connect timeout.
     */
    private val unreachable = MutableStateFlow(emptyMap<String, String>())

    /** Remembers [baseUrl] as unreachable and returns the non-retryable error that names its host only. */
    private fun markUnreachable(
        baseUrl: String,
        kind: String,
        e: AiProviderException,
    ): AiProviderException {
        val host = parseUrl(baseUrl)?.host?.takeIf { it.isNotEmpty() } ?: "provider"
        val reason = "$PROVIDER_UNREACHABLE_PREFIX$host ($kind)"
        // calls already in flight fail on their own; one WARN per host
        if (baseUrl !in unreachable.getAndUpdate { it + (baseUrl to reason) }) {
            logger.warn("AI provider '$providerId': $reason, later calls to it this run fail at once (${e.message})")
        }
        return AiProviderException(reason, retryable = false, cause = e)
    }

    // -- Private retry machinery --

    private suspend fun executeWithRetry(
        model: String,
        send: suspend () -> HttpResponse,
    ): ProviderResult {
        var lastException: Exception? = null
        // maxRetries counts retries after the first attempt, like ai.max-retries in the docs and HttpClientSettings.Retry
        val attempts = effectiveMaxRetries.value.coerceAtLeast(0) + 1
        val baseUrl = resolveBaseUrl()
        unreachable.value[baseUrl]?.let { throw AiProviderException(it, retryable = false) }

        for (attempt in 0 until attempts) {
            try {
                return executeAndHandle(model, send)
            } catch (e: AiProviderException) {
                lastException = e
                // a host that refuses or never answers the connect will not answer a retry either (a status means it answered)
                if (e.statusCode == null) connectFailureKind(e)?.let { throw markUnreachable(baseUrl, it, e) }
                // an IllegalArgumentException cause is a guard (SSRF) or request-building rejection, never transient
                if (!e.retryable || e.cause is IllegalArgumentException || attempt == attempts - 1) {
                    throw e
                }
                val retryAfterMs = (e as? AiProviderException.RateLimited)?.retryAfterMs
                val delayMs = RetryBackoff.delayMs(attempt + 1, RETRY_BASE_DELAY_MS, retryAfterMs)
                logger.warn(
                    "AI provider: attempt ${attempt + 1}/$attempts failed (${e.message}), retrying in ${delayMs}ms",
                )
                delay(delayMs)
            } catch (e: HttpRequestTimeoutException) {
                lastException = e
                if (attempt == attempts - 1) {
                    throw AiProviderException(
                        message = "Request timed out after ${effectiveTimeout.value}$streamHint",
                        retryable = true,
                        cause = e,
                    )
                }
                val delayMs = RetryBackoff.delayMs(attempt + 1, RETRY_BASE_DELAY_MS)
                logger.warn(
                    "AI provider: attempt ${attempt + 1}/$attempts timed out after ${effectiveTimeout.value}, retrying in ${delayMs}ms",
                )
                delay(delayMs)
            }
        }

        throw lastException ?: AiProviderException("Request failed after $attempts attempts")
    }

    private suspend fun executeAndHandle(
        model: String,
        send: suspend () -> HttpResponse,
    ): ProviderResult {
        if (streaming) {
            val deadline = runtime.value.streamDeadline
            val result =
                try {
                    // a wall-clock deadline: Default keeps it on real time even under a test scheduler's virtual clock
                    withContext(Dispatchers.Default) { withTimeoutOrNull(deadline.millis.milliseconds) { receive(model, send) } }
                } catch (e: SocketTimeoutException) {
                    throw streamStalled(e)
                } catch (e: AiProviderException) {
                    // the providers' `post` wraps a stall before the response headers
                    val cause = e.cause
                    throw if (cause is SocketTimeoutException) streamStalled(cause) else e
                }
            return result
                ?: throw streamTrip("ran past its total deadline of $deadline", KEY_STREAM_DEADLINE, cause = null)
        }
        return receive(model, send)
    }

    private suspend fun receive(
        model: String,
        send: suspend () -> HttpResponse,
    ): ProviderResult {
        val started = TimeSource.Monotonic.markNow()
        val httpResponse = send()
        val statusCode = httpResponse.status.value
        val limit = effectiveTimeout.value
        if (statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            throw errorOf(httpResponse, model, limit.millis.milliseconds - started.elapsedNow())
        }
        val headers = httpResponse.headers.entries().associate { (key, values) -> key.lowercase() to values.firstOrNull().orEmpty() }
        if (streaming) return ProviderResult("", statusCode, headers, readStream(httpResponse).toList())
        // The engine's request timeout stops at the response headers on CIO, so a body that trickles in was
        // unbounded; the body read gets what is left of ai.timeout. Streaming keeps its idle timeout instead.
        val body = withTimeoutOrNull(limit.millis.milliseconds - started.elapsedNow()) { httpResponse.bodyAsText() }
            ?: throw AiProviderException(message = "Request timed out after $limit$streamHint", retryable = true)
        return ProviderResult(body, statusCode, headers)
    }

    private fun streamStalled(cause: SocketTimeoutException): AiProviderException =
        streamTrip("sent no chunk for ${stallLimit(effectiveTimeout.value) ?: effectiveTimeout.value}", KEY_STREAM_STALL, cause)

    /** One WARN naming the cause and the key that bounds it, then a retryable error for [executeWithRetry]. */
    private fun streamTrip(
        what: String,
        key: String,
        cause: Throwable?,
    ): AiProviderException {
        val message = "AI provider '$providerId': streamed reply $what (raise $key if this provider is slow)"
        logger.warn("[ai] $message")
        return AiProviderException(message, retryable = true, cause = cause)
    }

    /** An error answer's body is read within [budget] (what is left of `ai.timeout`), like a 2xx one: it may trickle too. */
    private suspend fun errorOf(
        response: HttpResponse,
        model: String,
        budget: Duration,
    ): AiProviderException {
        val statusCode = response.status.value
        val bodyText = withTimeoutOrNull(budget) { response.bodyAsText() }
            ?: throw AiProviderException(
                message = "HTTP $statusCode: error body not read within ${effectiveTimeout.value}$streamHint",
                statusCode = statusCode,
                retryable = statusCode == HTTP_RATE_LIMITED || statusCode in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX,
            )
        val error = typedError(providerId, handleErrorStatus(statusCode, bodyText), statusCode, bodyText, model)
        val retryAfterMs = if (statusCode == HTTP_RATE_LIMITED) RetryBackoff.retryAfterMs(response.headers) else null
        return if (retryAfterMs != null) AiProviderException.RateLimited(retryAfterMs, error.message.orEmpty()) else error
    }
}

/** Prefix of the error of a call whose host could not be reached: `unreachable: <host> (<kind>)`. */
const val PROVIDER_UNREACHABLE_PREFIX: String = "unreachable: "

private const val MAX_CAUSE_DEPTH = 8

/** `connect timeout`, `connection refused` or `dns` when [e] or a cause is a failure to connect, else null. */
internal fun connectFailureKind(e: Throwable): String? =
    generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).firstNotNullOfOrNull { t ->
        val name = t::class.simpleName.orEmpty()
        val message = t.message.orEmpty().lowercase()
        when {
            name == "ConnectTimeoutException" || "connect timeout" in message || "connection timed out" in message -> "connect timeout"

            name == "ConnectException" || "connection refused" in message -> "connection refused"

            name == "UnresolvedAddressException" || name == "UnknownHostException" ||
                DNS_MARKERS.any { it in message } -> "dns"

            else -> null
        }
    }

private val DNS_MARKERS =
    listOf("unresolved address", "could not resolve", "couldn't resolve", "name or service not known", "nodename nor servname")

/** a 401 becomes [AiProviderException.AuthenticationFailed], a 404 naming a model [AiProviderException.ModelNotAvailable]; 403 stays non-retryable. */
private fun typedError(
    providerId: String,
    error: AiProviderException,
    statusCode: Int,
    bodyText: String,
    model: String,
): AiProviderException {
    val message = error.message.orEmpty()
    return when {
        error is AiProviderException.AuthenticationFailed || error is AiProviderException.ModelNotAvailable -> {
            error
        }

        statusCode == HttpStatusCode.Unauthorized.value -> {
            AiProviderException.AuthenticationFailed(providerId, message)
        }

        statusCode == HttpStatusCode.NotFound.value && bodyText.contains("model", ignoreCase = true) -> {
            AiProviderException.ModelNotAvailable(model, message)
        }

        else -> {
            error
        }
    }
}
