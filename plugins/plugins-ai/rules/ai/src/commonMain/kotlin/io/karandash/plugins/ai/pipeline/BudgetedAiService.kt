package io.karandash.plugins.ai.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.Logger

/**
 * an auxiliary AI call (hooks, constitutional review, enricher, digest) charged to the analyzers that
 * triggered it. Each response's tokens go to [charge] and are logged under [stage]; the call itself is never
 * refused here, so a spent run budget cannot drop it. The run's safety cap is checked per stage by the caller.
 */
internal class BudgetedAiService(
    private val delegate: AiService,
    private val stage: String,
    private val logger: Logger,
    private val charge: (tokens: Int) -> Unit,
) : AiService {
    override val isAvailable: Boolean get() = delegate.isAvailable

    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        val response = delegate.complete(request, providerId)
        charge(response.totalTokens)
        logger.info("AI $stage: ${response.totalTokens} tokens charged to the triggering analyzers")
        return response
    }
}
