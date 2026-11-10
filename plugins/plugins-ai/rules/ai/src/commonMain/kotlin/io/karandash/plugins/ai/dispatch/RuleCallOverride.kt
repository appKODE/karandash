package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `ai.analyzer.tokens.maxSystemPromptChars` caps the system prompt of every call of the rule; the
 * `model` and `maxTokens` a caller puts in the rule config element of one `validate` call
 * (description generation passes `ai.enrichment.model` and `ai.enrichment.maxTokens`) replace the model and
 * the response token limit of every call that run makes.
 */
internal data class RuleCallOverride(
    val model: String? = null,
    val maxTokens: Int? = null,
    /** `ai.analyzer.tokens.maxSystemPromptChars`: cap of the system prompt of every call; null leaves it whole. */
    val systemPromptChars: Int? = null,
) {
    val isEmpty: Boolean get() = model == null && maxTokens == null && systemPromptChars == null

    /** Analyzer path: every call through the rule's [AiService]. */
    class Service(
        private val delegate: AiService,
        private val override: RuleCallOverride,
    ) : AiService by delegate {
        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse =
            delegate.complete(
                request.copy(
                    model = override.model ?: request.model,
                    maxTokens = override.maxTokens ?: request.maxTokens,
                    systemPrompt = override.systemPromptChars?.let { request.systemPrompt?.take(it) } ?: request.systemPrompt,
                ),
                providerId,
            )
    }

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_TOKENS = "maxTokens"

        fun fromConfig(config: JsonElement): RuleCallOverride {
            val obj = config as? JsonObject ?: return RuleCallOverride()
            return RuleCallOverride(
                model = (obj[KEY_MODEL] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                maxTokens = (obj[KEY_MAX_TOKENS] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            )
        }
    }
}
