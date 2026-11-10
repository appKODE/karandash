package io.karandash.plugins.ai.core

/** Centralized env var name constants used by the AI module providers. */
object AiEnvVars {
    const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
    const val OPENAI_API_KEY = "OPENAI_API_KEY"
    const val GEMINI_API_KEY = "GEMINI_API_KEY"
    const val OLLAMA_HOST = "OLLAMA_HOST"

    /** Optional endpoint overrides (`ai.baseUrl` wins, the built-in endpoint is the fallback). */
    const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
    const val ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL"
    const val GEMINI_BASE_URL = "GEMINI_BASE_URL"
    const val CUSTOM_AI_API_KEY = "CUSTOM_AI_API_KEY"
    const val LITELLM_API_KEY = "LITELLM_API_KEY"
    const val LITELLM_BASE_URL = "LITELLM_BASE_URL"
    const val BIFROST_API_KEY = "BIFROST_API_KEY"
    const val BIFROST_BASE_URL = "BIFROST_BASE_URL"
    const val AI_CONCURRENCY = "AI_CONCURRENCY"
    const val CORE_CONCURRENCY = "CORE__CONCURRENCY"
    const val KARANDASH_AI_MAX_TOKEN_BUDGET = "KARANDASH_AI_MAX_TOKEN_BUDGET"
    const val KARANDASH_REVIEW_MODE = "KARANDASH_REVIEW_MODE"
    const val KARANDASH_AI_DRY_RUN = "KARANDASH_AI_DRY_RUN"
}
