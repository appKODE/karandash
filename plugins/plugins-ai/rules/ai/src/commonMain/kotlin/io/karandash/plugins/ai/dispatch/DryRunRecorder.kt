package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import kotlinx.atomicfu.atomic

/**
 * Dry run (`ai.dryRun`, `KARANDASH_AI_DRY_RUN`): stands in for the rule's provider and records
 * each fully built request instead of sending it; `AiService` calls report here via [record].
 * One instance per rule dispatch.
 */
internal class DryRunRecorder(
    private val delegate: AiProvider,
) : AiProvider by delegate {
    private val requests = atomic(0)
    private val chars = atomic(0)

    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): AiResponse {
        recordText(prompt, systemPrompt)
        return AiResponse(content = "", promptTokens = 0, completionTokens = 0, totalTokens = 0, model = model)
    }

    override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
        record(request)
        return AiResponse(content = "", promptTokens = 0, completionTokens = 0, totalTokens = 0, model = request.model)
    }

    fun record(request: AiCompletionRequest) = recordText(request.prompt, request.systemPrompt)

    private fun recordText(
        prompt: String,
        systemPrompt: String?,
    ) {
        requests.incrementAndGet()
        chars.addAndGet(prompt.length + (systemPrompt?.length ?: 0))
    }

    /** One-line report of what would have been sent, appended to the dry-run finding. */
    fun summary(): String {
        val total = chars.value
        return "Would send ${requests.value} request(s), $total chars (~${TokenUnit.charsToTokens(total)} tokens)"
    }
}
