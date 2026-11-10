package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.serialization.Serializable

/**
 * Resources a module consumed during a run, reported via [RuleModule.usage].
 *
 * @property tokens LLM tokens (input + output).
 * @property requests Outbound requests (LLM or API calls).
 * @property costUsd Estimated cost in USD, or `null` when unknown.
 */
@ExperimentalKarandashApi
@Serializable
public data class ModuleUsage(
    val tokens: Long = 0,
    val requests: Int = 0,
    val costUsd: Double? = null,
)
