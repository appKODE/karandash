package io.karandash.api.support

import kotlinx.coroutines.CancellationException

/**
 * Rethrows this exception if it is a [CancellationException].
 *
 * **Must be called** as the first statement in every `catch (e: Exception)` block
 * inside a `suspend fun`. Failing to rethrow `CancellationException` breaks
 * structured concurrency - the coroutine appears to complete normally instead
 * of propagating the cancellation signal.
 *
 * Usage:
 * ```kotlin
 * suspend fun doWork() {
 *     try {
 *         riskyOperation()
 *     } catch (e: Exception) {
 *         e.rethrowCancellation()
 *         logger.warn("Operation failed: ${e.message}")
 *     }
 * }
 * ```
 */
public fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

/**
 * Cancellation-safe replacement for [runCatching]: runs [block] and wraps any [Exception] in a
 * [Result.failure], except [CancellationException], which is rethrown so structured concurrency
 * keeps working. Errors (`OutOfMemoryError`, `StackOverflowError`, …) are not caught.
 *
 * Usable from suspend and plain code (the block is inlined).
 */
@Suppress("TooGenericExceptionCaught") // boundary: generic Result wrapper, cancellation rethrown
public inline fun <T> suspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        // boundary: suspendCatching is the sanctioned catch-all; cancellation is rethrown
        e.rethrowCancellation()
        Result.failure(e)
    }
