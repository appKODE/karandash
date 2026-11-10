package io.karandash.cache

import kotlinx.serialization.Serializable

/**
 * Atomic-friendly wrapper for lazily resolved values.
 *
 * Used with [kotlinx.coroutines.flow.MutableStateFlow] to replace
 * the `var resolved: Boolean` + `var cached: T?` pattern with a
 * single atomic state holder.
 *
 * @param T The cached value type.
 */
sealed class CachedValue<out T> {
    /** Value has not been resolved yet. */
    data object Unresolved : CachedValue<Nothing>()

    /** Value has been resolved (may be null if the upstream returned null). */
    @Serializable
    data class Resolved<T>(
        val value: T
    ) : CachedValue<T>()
}
