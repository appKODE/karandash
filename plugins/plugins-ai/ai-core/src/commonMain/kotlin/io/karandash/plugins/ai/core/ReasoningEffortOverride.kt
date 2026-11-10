package io.karandash.plugins.ai.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A reasoning effort for the provider calls made inside [with], over `ai.reasoningEffort`. Carried in the coroutine
 * context, so it reaches the provider through every [io.karandash.api.ai.AiService] wrapper without a request field.
 * Providers that do not read it (and bridge guests) ignore it.
 */
class ReasoningEffortOverride private constructor(
    val effort: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReasoningEffortOverride> {
        /** Effort sent when a reasoning model spent its whole output cap thinking: reasoning off. */
        const val NONE = "none"

        /** Runs [block] with its provider calls at reasoning [effort]. */
        suspend fun <T> with(
            effort: String,
            block: suspend () -> T,
        ): T = withContext(ReasoningEffortOverride(effort)) { block() }

        /** The effort set by an enclosing [with], or null. */
        suspend fun current(): String? = currentCoroutineContext()[Key]?.effort
    }
}
