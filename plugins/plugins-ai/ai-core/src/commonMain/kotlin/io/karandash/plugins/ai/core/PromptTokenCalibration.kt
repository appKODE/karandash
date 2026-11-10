package io.karandash.plugins.ai.core

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Per-model ratio of provider-reported prompt tokens to the [TokenUnit] estimate of the same requests, summed over
 * the run so far; [adjust] turns a budget in real tokens into estimate units for the next request's plan, so a
 * tokenizer denser than chars/4 does not overshoot `ai.context.budget`. Thread-safe: rules record concurrently.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class PromptTokenCalibration {
    private val totals = atomic<Map<String, Pair<Long, Long>>>(emptyMap())

    /** Adds one request (or a rule's requests) to [model]'s totals; ignored unless both counts are positive. */
    fun record(
        model: String,
        actualTokens: Int,
        estimatedTokens: Int,
    ) {
        if (actualTokens <= 0 || estimatedTokens <= 0) return
        totals.update { all ->
            val (actual, estimated) = all[model] ?: (0L to 0L)
            all + (model to (actual + actualTokens to estimated + estimatedTokens))
        }
    }

    /** Actual over estimated prompt tokens for [model] so far, clamped to [MIN_RATIO]..[MAX_RATIO]; 1.0 before any record. */
    fun ratio(model: String): Double =
        totals.value[model]?.let { (actual, estimated) -> (actual.toDouble() / estimated).coerceIn(MIN_RATIO, MAX_RATIO) } ?: 1.0

    /** [tokens] real tokens in estimate units for [model]: [tokens] divided by [ratio], at least 1. */
    fun adjust(
        model: String,
        tokens: Int,
    ): Int = (tokens / ratio(model)).toInt().coerceAtLeast(1)

    companion object {
        /** A ratio is never trusted below this: one odd reply must not double the budget. */
        const val MIN_RATIO = 0.5

        /** A ratio is never trusted above this: one odd reply must not halve the budget more than this. */
        const val MAX_RATIO = 2.0
    }
}
