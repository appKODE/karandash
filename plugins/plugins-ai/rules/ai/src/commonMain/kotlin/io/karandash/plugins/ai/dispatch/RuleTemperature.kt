package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse

/** `ai.temperatures.<rule>` (or `ai.<rule>.temperature`): replaces the temperature of every call the rule makes. */
internal object RuleTemperature {
    /** Analyzer path: every call through the rule's [AiService]. */
    class Service(
        private val delegate: AiService,
        private val temperature: Double,
    ) : AiService by delegate {
        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = delegate.complete(request.copy(temperature = temperature), providerId)
    }

    /** Direct-provider path of custom rules. */
    class Provider(
        private val delegate: AiProvider,
        private val temperature: Double,
    ) : AiProvider by delegate {
        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = delegate.complete(prompt, systemPrompt, model, maxTokens, this.temperature, jsonMode)

        override suspend fun completeRequest(request: AiCompletionRequest): AiResponse =
            delegate.completeRequest(request.copy(temperature = temperature))
    }
}
