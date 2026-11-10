package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.reflect.safeCast

/**
 * Coroutine-context element carrying the services of one validation run.
 *
 * Owned by a [RunSession]: values are added through [RunSession.provide] and are visible to
 * every coroutine already running with this element, because the element holds the live
 * copy-on-write map rather than a snapshot.
 */
@ExperimentalKarandashApi
public class RunScopedServices internal constructor() : AbstractCoroutineContextElement(Key) {
    private val values = atomic<Map<ServiceKey<*>, Any>>(emptyMap())

    /** Returns the value provided for [key], or `null`. */
    public operator fun <T : Any> get(key: ServiceKey<T>): T? = key.type.safeCast(values.value[key])

    internal fun <T : Any> put(
        key: ServiceKey<T>,
        value: T,
    ) {
        values.update { it + (key to value) }
    }

    public companion object Key : CoroutineContext.Key<RunScopedServices> {
        /** The services of the run the calling coroutine belongs to, or `null` outside a run. */
        public suspend fun current(): RunScopedServices? = coroutineContext[Key]
    }
}
