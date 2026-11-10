package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.PromptTokenCalibration
import io.karandash.plugins.ai.core.ReasoningEffortOverride
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.halveChanges
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import kotlinx.atomicfu.atomic

/**
 * Watches every call of one rule's [AiService]: feeds provider-reported prompt tokens against the [TokenUnit]
 * estimate of the same request into [calibration], and flags [truncated] once a reply used its whole `maxTokens`.
 *
 * An empty reply that used its whole `maxTokens` is a reasoning model that thought until the cap: the call is retried
 * once with reasoning off ([ReasoningEffortOverride.NONE]) before it counts as truncated, unless `ai.reasoningEffort`
 * ([configuredEffort]) is already `none`. The retry's reply is returned with both calls' tokens in `totalTokens`, so
 * the budget pays for both; its own prompt and completion tokens decide calibration and truncation.
 *
 * limit: `completionTokens >= maxTokens` stands in for finish_reason=length, which [AiCompletionResponse] does not
 * carry; switch to the real reason if the SDK ever exposes it. Custom rules on the direct-provider path are not watched.
 *
 * @lifecycle per-run -- one instance per rule dispatch
 */
internal class CallWatch(
    private val delegate: AiService,
    private val calibration: PromptTokenCalibration,
    private val configuredEffort: String? = null,
    private val logger: Logger = NoOpLogger,
) : AiService by delegate {
    private val truncatedFlag = atomic(false)

    /** True once any reply through this service hit its output cap. */
    val truncated: Boolean get() = truncatedFlag.value

    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        val first = call(request, providerId)
        val response = if (first.content.isBlank() && capped(first, request) && configuredEffort != ReasoningEffortOverride.NONE) {
            retryWithoutReasoning(request, providerId, first)
        } else {
            first
        }
        if (capped(response, request)) truncatedFlag.value = true
        return response
    }

    private suspend fun retryWithoutReasoning(
        request: AiCompletionRequest,
        providerId: String?,
        first: AiCompletionResponse,
    ): AiCompletionResponse {
        logger.warn("[ai] model=${request.model}: empty reply at its output cap (reasoning used it); retrying once with reasoning off")
        return try {
            val retry = ReasoningEffortOverride.with(ReasoningEffortOverride.NONE) { call(request, providerId) }
            retry.copy(totalTokens = retry.totalTokens + first.totalTokens)
        } catch (e: AiProviderException) {
            e.rethrowCancellation()
            logger.warn("[ai] model=${request.model}: retry with reasoning off failed (${e.message}); keeping the empty reply")
            first
        }
    }

    private suspend fun call(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse =
        delegate.complete(request, providerId).also { calibration.record(request.model, it.promptTokens, estimate(request)) }

    private fun capped(
        response: AiCompletionResponse,
        request: AiCompletionRequest,
    ): Boolean = response.completionTokens > 0 && response.completionTokens >= request.maxTokens

    private fun estimate(request: AiCompletionRequest): Int =
        TokenUnit.tokensOf(request.prompt) + TokenUnit.tokensOf(request.systemPrompt.orEmpty()) +
            request.contextBlocks.sumOf { TokenUnit.tokensOf(it) }
}

/**
 * A reply of [input] hit its output cap (finish_reason=length): runs each half of its diff once through [run], halves
 * from [halveChanges] (by file, else by hunk). Null when the diff is one hunk and cannot be halved. Halves are never
 * split again.
 */
internal suspend fun retryHalves(
    input: AiRuleDispatcher.DispatchInput,
    logger: Logger,
    run: suspend (AiRuleDispatcher.DispatchInput) -> List<Finding>?,
): List<Finding>? {
    val (first, second) = halveChanges(input.mr.changes) ?: return null
    logger.warn("[ai] rule=${input.ruleId}: reply hit its output cap; retrying the diff as two halves")
    val rulePaths = input.rulePaths ?: input.mr.changes.mapTo(mutableSetOf()) { it.path }
    return listOf(first, second).flatMap { half ->
        run(input.copy(mr = input.mr.copy(changes = half), rulePaths = rulePaths)).orEmpty()
    }
}

/** The findings of a capped reply next to its [halves]: the halves replace the cut reply, so its parse error goes. */
internal fun replaceCapped(
    capped: List<Finding>,
    halves: List<Finding>,
): List<Finding> = capped.filterNot { it.metadata[WellKnownMetadataKeys.ERROR] == AiRuleAnalyzerUtils.PARSE_ERROR } + halves
