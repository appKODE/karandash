package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import kotlin.reflect.KClass

/**
 * Typed key of a run-scoped service in [RunScopedServices].
 *
 * Equality is identity: two keys with the same [name] are different keys. [type] is used
 * only for the safe cast on lookup.
 */
@ExperimentalKarandashApi
public class ServiceKey<T : Any>(
    public val name: String,
    public val type: KClass<T>,
) {
    override fun toString(): String = "ServiceKey($name)"
}

/** Creates a [ServiceKey] for [T]. */
@ExperimentalKarandashApi
public inline fun <reified T : Any> serviceKey(name: String): ServiceKey<T> = ServiceKey(name, T::class)
