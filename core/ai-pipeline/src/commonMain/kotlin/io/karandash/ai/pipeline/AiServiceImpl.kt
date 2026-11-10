@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.ai.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiProviderSource
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiService
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.run.dumpJson
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import io.karandash.security.TextRedactor
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Default [AiService] implementation that routes requests through
 * registered [AiInterceptorModule]s and then to an [AiProviderModule].
 *
 * Interceptors are composed in ascending priority order (lowest = outermost).
 * The terminal link in the chain calls [AiProviderModule.complete].
 *
 * @param registry Module registry for provider and interceptor lookup.
 * @param defaultProviderId Provider ID to use when callers pass `providerId = null`.
 * @param localProviders Already-initialized providers that are not in the registry (e.g. the
 *   `custom` OpenAI-compatible provider built from config), keyed by provider ID. Looked up
 *   before the registry and never re-initialized.
 * @param providerSource Run-scoped provider source; when set it replaces the registry lookup
 *   (configured run handles wrapped with model fallback and the response cache).
 * @param ruleId Rule this service calls on behalf of (see [forRule]); passed to every hook context.
 * @param sourceModuleId Module making the calls (see [forRule]); passed to [AiInterceptContext].
 * @param logger Receives the per-call provider selection at info level.
 * @param onResponse Sees every final response (after transformers), e.g. to meter token usage.
 */
class AiServiceImpl private constructor(
    private val registry: ModuleRegistry,
    private val defaultProviderId: String?,
    private val debugLogger: ((String) -> Unit)?,
    private val localProviders: Map<String, AiProviderModule>,
    private val providerSource: AiProviderSource?,
    private val ruleId: String?,
    private val sourceModuleId: String?,
    private val logger: Logger?,
    private val onResponse: ((AiCompletionResponse) -> Unit)?,
    private val fallbackProviders: FallbackProviders,
    private val dryRunSink: ((AiCompletionRequest) -> Unit)? = null,
    private val promptSink: (suspend (AiCompletionRequest) -> Unit)? = null,
) : AiService {
    /** Calls made through this view, numbering its `--dump-dir` files. */
    private val calls = atomic(0)

    constructor(
        registry: ModuleRegistry,
        defaultProviderId: String? = null,
        debugLogger: ((String) -> Unit)? = null,
        localProviders: Map<String, AiProviderModule> = emptyMap(),
        providerSource: AiProviderSource? = null,
        ruleId: String? = null,
        sourceModuleId: String? = null,
        logger: Logger? = null,
        onResponse: ((AiCompletionResponse) -> Unit)? = null,
    ) : this(
        registry,
        defaultProviderId,
        debugLogger,
        localProviders,
        providerSource,
        ruleId,
        sourceModuleId,
        logger,
        onResponse,
        FallbackProviders(),
    )

    override val isAvailable: Boolean
        get() = providerSource != null || localProviders.isNotEmpty() || registry.aiProviders().isNotEmpty()

    /**
     * A view of this service for [ruleId]: calls without an explicit provider go to [providerId]
     * (the rule's `ruleProviders` entry) or this service's default, and hook contexts carry [ruleId].
     * [onResponse] sees each response of this view (the rule's usage meter).
     * With [dryRunSink] set the view never calls a provider: each request is fully assembled
     * (processors, redactors), handed to the sink and answered with an empty zero-token response.
     * [promptSink] sees each request exactly as the provider gets it (after redactors and
     * interceptors), or as [dryRunSink] gets it in a dry run: the prompt dump.
     */
    fun forRule(
        ruleId: String,
        providerId: String?,
        sourceModuleId: String? = null,
        onResponse: ((AiCompletionResponse) -> Unit)? = null,
        dryRunSink: ((AiCompletionRequest) -> Unit)? = null,
        promptSink: (suspend (AiCompletionRequest) -> Unit)? = null,
    ): AiServiceImpl =
        AiServiceImpl(
            registry = registry,
            defaultProviderId = providerId ?: defaultProviderId,
            debugLogger = debugLogger,
            localProviders = localProviders,
            providerSource = providerSource,
            ruleId = ruleId,
            sourceModuleId = sourceModuleId,
            logger = logger,
            onResponse = onResponse,
            fallbackProviders = fallbackProviders,
            dryRunSink = dryRunSink,
            promptSink = promptSink,
        )

    /**
     * Shuts down every registry provider this service (or any of its [forRule] views) initialized
     * on the fallback path (no [providerSource]). Local and source providers belong to their
     * creator and are left alone. Idempotent; a later call re-initializes on demand.
     */
    suspend fun shutdown() = fallbackProviders.shutdownAll()

    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        val effectiveProviderId = providerId ?: defaultProviderId
        if (dryRunSink != null) {
            val assembled = applyRedactors(applyPromptProcessors(request, effectiveProviderId), effectiveProviderId)
            val sent = assembled.withContextInPrompt()
            promptSink?.invoke(sent)
            dryRunSink.invoke(sent)
            val dumped = dumpRequest(sent)
            logger?.info("[ai] dry run: not sent rule=${ruleId ?: "-"} model=${request.model} prompt=${assembled.prompt.length} chars")
            val empty = AiCompletionResponse(content = "", promptTokens = 0, completionTokens = 0, totalTokens = 0, model = request.model)
            return empty.also { response -> dumped?.let { dumpJson("$it.response.json") { response } } }
        }
        val provider = resolveProvider(effectiveProviderId)
        logger?.info(
            "[ai] provider=${provider.providerId} model=${request.model} rule=${ruleId ?: "-"} " +
                "(requested=${effectiveProviderId ?: "default"})",
        )
        val interceptors = registry.aiInterceptors()

        val processed = applyPromptProcessors(request, effectiveProviderId)
        val redacted = applyRedactors(processed, effectiveProviderId)

        val context = AiInterceptContext(
            sourceModuleId = sourceModuleId,
            ruleId = ruleId,
            providerId = effectiveProviderId,
        )

        val sent = if (provider.supportsContextBlocks) redacted else redacted.withContextInPrompt()
        val dumped = dumpRequest(sent)
        val response = buildChain(interceptors, provider, context).proceed(sent)
        val head = "AI response [model=${request.model}, provider=$effectiveProviderId, rule=${ruleId ?: "-"}]: " +
            "${response.totalTokens} tokens (prompt=${response.promptTokens}, completion=${response.completionTokens}), " +
            "${response.content.length} chars"
        // The full response body only with --trace / KARANDASH_AI_TRACE=1; --verbose keeps the one line
        debugLogger?.invoke(
            if (AiTrace.enabled) AiTrace.section("ai_response", head, TextRedactor.redactForLog(response.content)) else head,
        )
        return applyResponseTransformers(response, sent, effectiveProviderId).also { final ->
            onResponse?.invoke(final)
            dumped?.let { dumpJson("$it.response.json") { final } }
        }
    }

    /**
     * `--dump-dir`: writes [sent] as `ai/<rule>/call-<n>.request.json` and returns `ai/<rule>/call-<n>` for the
     * response file; `null` when the run does not dump.
     */
    private suspend fun dumpRequest(sent: AiCompletionRequest): String? {
        if (RunScopedServices.current()?.get(RunServiceKeys.DUMP) == null) return null
        val base = "ai/${ruleId ?: "service"}/call-${calls.incrementAndGet()}"
        dumpJson("$base.request.json") { sent }
        return base
    }

    private suspend fun resolveProvider(providerId: String?): AiProviderModule {
        providerId?.let { localProviders[it] }?.let { return it }
        if (providerSource != null) {
            return providerSource.provider(providerId)
                ?: throw AiProviderException("AI provider '${providerId ?: "default"}' is unavailable with current config")
        }
        return fallbackProviders.getOrInit(providerId) { initializeFromRegistry(providerId) }
    }

    private suspend fun initializeFromRegistry(providerId: String?): AiProviderModule {
        if (providerId != null) {
            val provider = registry.findAiProvider(providerId)
                ?: throw AiProviderException("AI provider '$providerId' not found")
            if (!provider.initialize(initializationContext())) {
                provider.shutdown() // a failed initialize may have allocated; it is not tracked for shutdownAll
                throw AiProviderException("AI provider '$providerId' is unavailable with current environment")
            }
            return provider
        }
        val providers = registry.aiProviders()
        if (providers.isEmpty()) {
            throw AiProviderException("No AI provider registered")
        }
        for (provider in providers) {
            if (provider.initialize(initializationContext())) {
                return provider
            }
            provider.shutdown()
        }
        throw AiProviderException("No AI provider is available with current environment")
    }

    private fun initializationContext(): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val hostVersion: String = io.karandash.Version.VERSION
            override val workingDirectory: String = "."

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            // Same denylist as plugin contexts built by PluginBootstrap.
            override fun env(name: String): String? = if (name in KarandashEnvSecurity.DENYLIST) null else PlatformEnvironment.getEnv(name)
        }

    private suspend fun applyPromptProcessors(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionRequest {
        val processors = registry.aiPromptProcessors()
        if (processors.isEmpty()) return request

        var current = request
        val ctx = AiPromptProcessorContext(
            ruleId = ruleId,
            model = request.model,
            providerId = providerId,
        )
        for (processor in processors) {
            val newPrompt = processor.processPrompt(current.prompt, ctx)
            val newSystem = processor.processSystemPrompt(current.systemPrompt, ctx)
            current = current.copy(prompt = newPrompt, systemPrompt = newSystem)
        }
        return current
    }

    /**
     * Registry redactors, then the built-in secret patterns over the fully assembled prompt:
     * enrichment, ticket text, prior findings and processor output are redacted too, not just the diff.
     */
    private suspend fun applyRedactors(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionRequest {
        var prompt = request.prompt
        var systemPrompt = request.systemPrompt
        val ctx = AiRedactorContext(ruleId = ruleId, providerId = providerId)
        for (redactor in registry.aiRedactors()) {
            prompt = redactor.redact(prompt, ctx)
            if (systemPrompt != null) {
                systemPrompt = redactor.redact(systemPrompt, ctx)
            }
        }
        val contextBlocks = request.contextBlocks.map { block ->
            TextRedactor.redact(registry.aiRedactors().fold(block) { text, redactor -> redactor.redact(text, ctx) })
        }
        return request.copy(
            prompt = TextRedactor.redact(prompt),
            systemPrompt = systemPrompt?.let(TextRedactor::redact),
            contextBlocks = contextBlocks,
        )
    }

    private suspend fun applyResponseTransformers(
        response: AiCompletionResponse,
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        val transformers = registry.aiResponseTransformers()
        if (transformers.isEmpty()) return response

        var current = response
        val ctx = AiResponseTransformerContext(ruleId = ruleId, providerId = providerId)
        for (transformer in transformers) {
            current = transformer.transform(current, request, ctx)
        }
        return current
    }

    /**
     * Builds a chain by folding interceptors right-to-left over the terminal provider call.
     * The interceptor with the lowest priority becomes the outermost (called first).
     */
    private fun buildChain(
        interceptors: List<AiInterceptorModule>,
        provider: AiProviderModule,
        context: AiInterceptContext,
    ): AiInterceptChain {
        var chain = AiInterceptChain { req ->
            promptSink?.invoke(req)
            provider.complete(req)
        }

        for (interceptor in interceptors.asReversed()) {
            val next = chain
            chain = AiInterceptChain { req ->
                interceptor.intercept(req, context, next)
            }
        }
        return chain
    }
}

/**
 * Registry providers initialized by the [AiServiceImpl] fallback path, keyed by requested provider
 * ID (`null` = first available). Shared by a service and all its [AiServiceImpl.forRule] views so a
 * provider is initialized once per run and shut down once.
 */
private class FallbackProviders {
    private val mutex = Mutex()
    private val byKey = MutableStateFlow<Map<String?, AiProviderModule>>(emptyMap())

    suspend fun getOrInit(
        key: String?,
        init: suspend () -> AiProviderModule,
    ): AiProviderModule =
        mutex.withLock {
            byKey.value[key] ?: init().also { provider -> byKey.update { it + (key to provider) } }
        }

    suspend fun shutdownAll() {
        val initialized = mutex.withLock { byKey.getAndUpdate { emptyMap() } }
        initialized.values.distinct().forEach { it.shutdown() }
    }
}
