package io.karandash.plugins.ai.core.config

import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.sdk.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Response token configuration for AI rule analyzers. Read from
 * `ai.analyzer.tokens`.
 *
 * @property minResponse Floor for auto-scaled response tokens.
 * @property maxAutoResponse Ceiling for auto-scaled response tokens.
 * @property charsPerTokenEstimate Approximate characters per token for English/code text.
 * @property maxSystemPromptChars Cap of the system prompt of every analyzer call; null leaves it whole.
 */
@Serializable
data class AiAnalyzerTokensConfig(
    val minResponse: Int = DEFAULT_MIN_RESPONSE,
    val maxAutoResponse: Int = DEFAULT_MAX_AUTO_RESPONSE,
    val charsPerTokenEstimate: Int = TokenUnit.CHARS_PER_TOKEN,
    val maxSystemPromptChars: Int? = null,
) {
    init {
        require(maxSystemPromptChars == null || maxSystemPromptChars > 0) { "maxSystemPromptChars must be positive" }
        require(charsPerTokenEstimate > 0) { "charsPerTokenEstimate must be positive" }
        require(minResponse in MIN_RESPONSE_FLOOR..MIN_RESPONSE_CEILING) {
            "minResponse must be in $MIN_RESPONSE_FLOOR..$MIN_RESPONSE_CEILING"
        }
        require(maxAutoResponse in MAX_AUTO_FLOOR..MAX_AUTO_CEILING) {
            "maxAutoResponse must be in $MAX_AUTO_FLOOR..$MAX_AUTO_CEILING"
        }
    }

    companion object {
        /**
         * Decodes an `ai.analyzer.tokens` block. An invalid block (wrong type, out-of-range value) is reported
         * through [logger] and reads as the defaults: config never throws.
         */
        fun parse(
            element: JsonElement,
            logger: Logger?,
        ): AiAnalyzerTokensConfig =
            try {
                KarandashJson.Default.decodeFromJsonElement(serializer(), element)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException, so a malformed value lands here too
                logger?.warn("AI plugin: ai.analyzer.tokens is invalid (${e.message}); using the defaults")
                AiAnalyzerTokensConfig()
            }

        const val DEFAULT_MIN_RESPONSE = 1500
        const val DEFAULT_MAX_AUTO_RESPONSE = 8000

        private const val MIN_RESPONSE_FLOOR = 500
        private const val MIN_RESPONSE_CEILING = 4000
        private const val MAX_AUTO_FLOOR = 1000
        private const val MAX_AUTO_CEILING = 32000
    }
}

/**
 * Configuration for AI rule analyzer behaviour.
 *
 * ```yaml
 * ai:
 *   analyzer:
 *     tokens:
 *       min-response: 2000
 *       max-auto-response: 12000
 * ```
 */
@Serializable
data class AiAnalyzerConfig(
    val tokens: AiAnalyzerTokensConfig = AiAnalyzerTokensConfig(),
)
