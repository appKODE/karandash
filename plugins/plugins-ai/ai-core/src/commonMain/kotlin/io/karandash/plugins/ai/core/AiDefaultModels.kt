package io.karandash.plugins.ai.core

/**
 * Default model ids, one place per provider. Used when neither `ai.model` nor a per-rule
 * `models` entry names one. [cheapFor] picks the low-cost model for auxiliary calls
 * (hooks, digests, self-critique) so they match the configured provider.
 */
object AiDefaultModels {
    const val ANTHROPIC = "claude-sonnet-5-5"
    const val ANTHROPIC_CHEAP = "claude-haiku-5-5"

    /**
     * a current low-cost OpenAI model ($0.75 / $4.50 per 1M tokens, https://developers.openai.com/api/docs/pricing,
     * checked 2026-10-25); `gpt-4o-mini` is two generations old.
     */
    const val OPENAI = "gpt-5.4-mini"

    /**
     * `gemini-2.0-flash` is shut down (https://ai.google.dev/gemini-api/docs/models, checked 2026-10-25);
     * `gemini-3.5-flash-lite` is the stable low-cost model Google recommends for new projects.
     */
    const val GEMINI = "gemini-3.5-flash-lite"

    /** Provider-agnostic fallback when the provider is unknown (OpenAI-compatible endpoints). */
    const val GENERIC = OPENAI

    private const val PROVIDER_ANTHROPIC = "anthropic"
    private const val PROVIDER_GEMINI = "gemini"
    private const val PROVIDER_OPENAI = "openai"

    /**
     * Low-cost model for [providerId] (case-insensitive). Any other provider (Ollama, `custom`, a gateway) gets
     * [configuredModel] (`ai.model`), since an OpenAI id means nothing to it; [GENERIC] only when unset.
     */
    fun cheapFor(
        providerId: String?,
        configuredModel: String? = null,
    ): String =
        when (providerId?.lowercase()) {
            PROVIDER_ANTHROPIC -> ANTHROPIC_CHEAP
            PROVIDER_GEMINI -> GEMINI
            PROVIDER_OPENAI -> OPENAI
            else -> configuredModel?.takeIf { it.isNotBlank() } ?: GENERIC
        }
}
