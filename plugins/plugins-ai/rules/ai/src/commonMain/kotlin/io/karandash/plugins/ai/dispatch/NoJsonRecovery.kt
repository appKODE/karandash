package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils.ExtractionStrategy
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.sdk.Logger

/**
 * `ai.filter.attemptJsonRecovery: false`: a response whose JSON was cut off (it would only parse
 * after [AiRuleAnalyzerUtils.extractJson] closes the open brackets) is replaced with empty content,
 * so the analyzer reports it as unparseable instead of acting on a partial finding list.
 */
internal object NoJsonRecovery {
    fun discardTruncated(
        content: String,
        ruleId: String,
        logger: Logger,
    ): String {
        if (AiRuleAnalyzerUtils.diagnoseExtraction(content).first != ExtractionStrategy.TRUNCATION_RECOVERY) return content
        logger.warn("AI plugin [$ruleId]: response JSON is truncated; discarded (ai.filter.attemptJsonRecovery: false)")
        return ""
    }

    /** Analyzer path: every call through the rule's [AiService]. */
    class Service(
        private val delegate: AiService,
        private val ruleId: String,
        private val logger: Logger,
    ) : AiService by delegate {
        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            val response = delegate.complete(request, providerId)
            return response.copy(content = discardTruncated(response.content, ruleId, logger))
        }
    }

    /** Direct-provider path of custom rules. */
    class Provider(
        private val delegate: AiProvider,
        private val ruleId: String,
        private val logger: Logger,
    ) : AiProvider by delegate {
        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            val response = delegate.complete(prompt, systemPrompt, model, maxTokens, temperature, jsonMode)
            return response.copy(content = discardTruncated(response.content, ruleId, logger))
        }

        override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
            val response = delegate.completeRequest(request)
            return response.copy(content = discardTruncated(response.content, ruleId, logger))
        }
    }
}
