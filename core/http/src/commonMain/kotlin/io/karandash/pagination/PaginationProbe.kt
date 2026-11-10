package io.karandash.pagination

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Tells a caller whether a pagination cap cut one of its requests short, so it can say "more than N" instead of
 * passing a partial list off as complete (a merge request's commits stopped by `http.pagination.maxPages`).
 */
class PaginationProbe private constructor() : AbstractCoroutineContextElement(Key) {
    private val truncated = atomic(false)

    private fun mark() {
        truncated.value = true
    }

    private fun wasTruncated(): Boolean = truncated.value

    companion object Key : CoroutineContext.Key<PaginationProbe> {
        /** Runs [block] and returns its result with whether any paginated request inside it stopped at a cap. */
        suspend fun <T> probe(block: suspend () -> T): Pair<T, Boolean> {
            val probe = PaginationProbe()
            val result = withContext(probe) { block() }
            return result to probe.wasTruncated()
        }

        /** Marks the enclosing [probe] (if any) as truncated; called wherever a cap stops a loop with items left. */
        suspend fun markTruncated() {
            currentCoroutineContext()[Key]?.mark()
        }
    }
}
