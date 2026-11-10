package io.karandash.plugins.ai

import io.karandash.api.ai.AiProviderException
import io.karandash.api.finding.SkipCategory
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.dispatch.TrivialDiff
import io.karandash.utils.finding.FindingFactory

internal object AiRuleErrorHandlers {
    const val META_ERROR_KEY: String = "error"
    const val ERR_NOT_INITIALIZED: String = "AI provider not initialized"

    fun providerError(
        ruleId: String,
        e: AiProviderException,
        state: AiRuleInitState,
    ): List<Finding> {
        // One INFO line per run says the AI is unavailable (healthFinding); each call's detail is DEBUG
        state.runtimeContext.logger.debug(
            "AI plugin: Provider error for $ruleId: ${e.message} (status=${e.statusCode}, retryable=${e.retryable})",
        )
        return if (state.failOnError) {
            listOf(
                FindingFactory.error(
                    rule = ruleId,
                    message = "AI analysis failed: ${e.message}",
                    metadata = mapOf(META_ERROR_KEY to "provider_error", "retryable" to e.retryable.toString()),
                ),
            )
        } else {
            emptyList()
        }
    }

    fun unexpectedError(
        ruleId: String,
        e: Exception,
        state: AiRuleInitState,
    ): List<Finding> {
        state.runtimeContext.warn("AI plugin: Unexpected error for $ruleId: ${e.message}")
        return if (state.failOnError) {
            listOf(
                FindingFactory.error(
                    rule = ruleId,
                    message = "AI analysis failed unexpectedly: ${e.message}",
                    metadata = mapOf(META_ERROR_KEY to "unexpected_error"),
                ),
            )
        } else {
            emptyList()
        }
    }

    /**
     * The skip reason of a failed AI call. The exception message stays in the log: it can carry urls or a
     * response body, and the reason reaches the MR comment.
     */
    fun providerErrorReason(e: Exception): String {
        val message = e.message.orEmpty()
        val detail = when {
            e is AiProviderException && e.statusCode != null -> "HTTP ${e.statusCode}"
            TIMED_OUT in message.lowercase() -> TIMED_OUT
            else -> "call failed, see the job log"
        }
        return "$PROVIDER_ERROR_PREFIX$detail"
    }

    const val PROVIDER_ERROR_PREFIX: String = "provider error: "
    private const val TIMED_OUT = "timed out"

    fun errorFinding(
        ruleId: String,
        message: String,
    ): List<Finding> = listOf(FindingFactory.error(rule = ruleId, message = message))

    fun categorizeSkipReason(reason: String): SkipCategory {
        val lower = reason.lowercase()
        return when {
            lower.startsWith(TrivialDiff.REASON_PREFIX) -> SkipCategory.AI_TRIVIAL_DIFF
            "budget" in lower -> SkipCategory.AI_BUDGET_EXHAUSTED
            "gated" in lower -> SkipCategory.AI_GATED
            "rate" in lower -> SkipCategory.AI_RATE_LIMITED
            "dry run" in lower -> SkipCategory.AI_DRY_RUN
            "size policy" in lower -> SkipCategory.AI_SIZE_POLICY
            "timeout" in lower -> SkipCategory.AI_PROVIDER_ERROR
            "required enrichment" in lower -> SkipCategory.MISSING_REQUIRED_ENRICHMENT
            else -> SkipCategory.OTHER
        }
    }
}
