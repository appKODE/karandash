package io.karandash.conditions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Mutable implementation of [PredicateRegistry].
 *
 * Provides a map-backed registry for condition predicates.
 * Thread-safe via [MutableStateFlow] with immutable map snapshots (copy-on-write).
 *
 * @param C The context type that registered predicates evaluate against.
 */
class MutablePredicateRegistry<C> : PredicateRegistry<C> {
    private val predicates = MutableStateFlow(emptyMap<String, ConditionPredicate<C>>())

    override fun get(name: String): ConditionPredicate<C>? = predicates.value[name]

    override fun exists(name: String): Boolean = name in predicates.value

    override fun all(): List<ConditionPredicate<C>> = predicates.value.values.toList()

    override fun register(predicate: ConditionPredicate<C>) {
        predicates.update { it + (predicate.name to predicate) }
    }
}
