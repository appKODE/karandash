package io.karandash.testfixtures

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService

/** [AiService] that answers every call with [content] and records the requests it received. */
class FakeAiService(
    private val content: String,
    private val totalTokens: Int = 500,
) : AiService {
    val requests = mutableListOf<AiCompletionRequest>()
    override val isAvailable: Boolean = true

    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        requests += request
        return AiCompletionResponse(content, promptTokens = totalTokens / 2, completionTokens = totalTokens / 2, totalTokens, request.model)
    }
}
