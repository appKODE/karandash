package io.karandash.plugins.ai.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Salts the response-cache key of the provider calls made inside [with], so a later review-comments sample never
 * reads an earlier sample's entry (it is a new reply, not a replay) while a rerun of the same run still gets every
 * sample from the cache. The provider's own prompt-prefix cache is untouched. Carried in the coroutine context like
 * [ReasoningEffortOverride].
 */
class ResponseCacheSalt private constructor(
    val salt: String
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResponseCacheSalt> {
        /** Runs [block] with [salt] added to the response-cache key of its provider calls. */
        suspend fun <T> with(
            salt: String,
            block: suspend () -> T,
        ): T = withContext(ResponseCacheSalt(salt)) { block() }

        /** The salt of the enclosing [with], null outside one. */
        suspend fun current(): String? = currentCoroutineContext()[Key]?.salt
    }
}
