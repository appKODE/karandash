package io.karandash.eval

import io.karandash.api.ai.AiProviderModule
import io.karandash.platform.PlatformEnvironment
import io.karandash.plugins.ai.providers.AbstractAiProvider
import io.karandash.plugins.ai.providers.AnthropicProvider
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.testfixtures.TestPluginContext

/**
 * Live mode (`KARANDASH_EVAL_LIVE=1`): answers analyzer calls from a real provider.
 *
 * - `KARANDASH_EVAL_PROVIDER`: `anthropic` (default) or `openai` (any OpenAI-compatible endpoint)
 * - `KARANDASH_EVAL_MODEL`: model id; defaults to the provider's default model
 * - `KARANDASH_EVAL_BASE_URL`: optional endpoint override
 * - credentials: the provider's usual `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`
 */
object LiveResponder {
    const val LIVE_ENV = "KARANDASH_EVAL_LIVE"
    const val RECORD_ENV = "KARANDASH_EVAL_RECORD"
    const val UPDATE_BASELINE_ENV = "KARANDASH_EVAL_UPDATE_BASELINE"
    private const val PROVIDER_ENV = "KARANDASH_EVAL_PROVIDER"
    private const val MODEL_ENV = "KARANDASH_EVAL_MODEL"
    private const val BASE_URL_ENV = "KARANDASH_EVAL_BASE_URL"

    fun flag(name: String): Boolean = PlatformEnvironment.getEnv(name) == "1"

    suspend fun create(): Pair<EvalRunner.Responder, AiProviderModule> {
        val provider: AbstractAiProvider = when (val name = PlatformEnvironment.getEnv(PROVIDER_ENV) ?: "anthropic") {
            "anthropic" -> AnthropicProvider()
            "openai" -> OpenAiProvider()
            else -> error("$PROVIDER_ENV=$name: expected anthropic or openai")
        }
        val keyVar = checkNotNull(provider.apiKeyEnvVar) { "${provider.providerId} declares no API key variable" }
        val key = checkNotNull(PlatformEnvironment.getEnv(keyVar)) { "$LIVE_ENV=1 needs $keyVar" }
        provider.configure(apiKey = key, baseUrl = PlatformEnvironment.getEnv(BASE_URL_ENV))
        check(provider.initialize(TestPluginContext(envValues = mapOf(keyVar to key)))) {
            "${provider.providerId} failed to initialize"
        }
        val model = PlatformEnvironment.getEnv(MODEL_ENV) ?: provider.defaultModel
        val responder = EvalRunner.Responder { _, _, request ->
            provider.complete(request.copy(model = model)).content
        }
        return responder to provider
    }
}
