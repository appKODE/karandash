package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable

/**
 * Token usage of one rule dispatch.
 *
 * @param budgetTokens Tokens the dispatch charged to its budget.
 * @param promptTokens Input tokens reported by the provider responses.
 * @param completionTokens Output tokens reported by the provider responses.
 * @param cacheReadTokens Input tokens served from the provider's prompt cache (part of [promptTokens]).
 * @param cacheWriteTokens Input tokens written to the provider's prompt cache (part of [promptTokens]).
 */
@Serializable
internal data class DispatchUsage(
    val budgetTokens: Int,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/**
 * Counts the input/output tokens of the calls made through it, plus analyzer calls made through
 * `AiService` and reported via [record]; one instance per rule dispatch.
 */
internal class UsageMeteringAiProvider(
    private val delegate: AiProvider,
) : AiProvider by delegate {
    private val prompt = atomic(0)
    private val completion = atomic(0)
    private val cacheRead = atomic(0)
    private val cacheWrite = atomic(0)

    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): AiResponse =
        delegate.complete(prompt, systemPrompt, model, maxTokens, temperature, jsonMode).also {
            record(it.promptTokens, it.completionTokens, it.cacheReadTokens, it.cacheWriteTokens)
        }

    override suspend fun completeRequest(request: AiCompletionRequest): AiResponse =
        delegate.completeRequest(request).also {
            record(it.promptTokens, it.completionTokens, it.cacheReadTokens, it.cacheWriteTokens)
        }

    fun record(
        promptTokens: Int,
        completionTokens: Int,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
    ) {
        prompt.addAndGet(promptTokens)
        completion.addAndGet(completionTokens)
        cacheRead.addAndGet(cacheReadTokens)
        cacheWrite.addAndGet(cacheWriteTokens)
    }

    fun usage(budgetTokens: Int): DispatchUsage =
        DispatchUsage(budgetTokens, prompt.value, completion.value, cacheRead.value, cacheWrite.value)
}
