package io.karandash.plugins.ai.providers

import io.karandash.cache.TieredCache
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.sdk.Logger

/**
 * The one wrapper chain over a base provider, built by both the run's `AiService` source
 * ([RunAiProviderSource]) and the main AI rule: `ai.modelFallback`, then the response [cache].
 * `ai.consensusModels` is a finding-level vote, run by the rule dispatcher, not a wrapper here.
 *
 * @param keyScope Extra response-cache key component, e.g. the provider base URL.
 * @param onFallback Called with the failed model and its error before the next fallback model is tried.
 */
fun AiProvider.withResponseChain(
    cfg: AiPluginConfig,
    cache: TieredCache<String, AiResponse>,
    logger: Logger,
    keyScope: String = "",
    onFallback: (String, String) -> Unit = { _, _ -> },
): AiProvider {
    val withFallback = if (cfg.modelFallback.isNotEmpty()) FallbackAiProvider(this, cfg.modelFallback, logger, onFallback) else this
    return CachingAiProvider(withFallback, cache, keyScope, logger)
}
