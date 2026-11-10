package io.karandash.plugins.ai.dispatch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Runs [block] on every item, at most [maxParallel] at once, and returns the results in item order. */
internal suspend fun <T, R> mapParallel(
    items: List<T>,
    maxParallel: Int,
    block: suspend (T) -> R,
): List<R> {
    val permits = Semaphore(maxParallel.coerceAtLeast(1))
    return coroutineScope { items.map { async { permits.withPermit { block(it) } } }.awaitAll() }
}
