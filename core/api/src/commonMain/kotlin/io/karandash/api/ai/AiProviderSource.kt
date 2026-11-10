package io.karandash.api.ai

import io.karandash.api.ExperimentalKarandashApi

/** Hands out the run's configured AI providers (wrapped with the run's cache/fallback chain). */
@ExperimentalKarandashApi
public fun interface AiProviderSource {
    /**
     * Returns the provider for [providerId], or the configured default when `null`; `null` when
     * it is unknown or cannot be initialized. The instance is run-owned: callers must not close it.
     */
    public suspend fun provider(providerId: String?): AiProviderModule?
}
