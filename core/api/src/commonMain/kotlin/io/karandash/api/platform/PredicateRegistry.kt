package io.karandash.api.platform

import io.karandash.api.context.ConditionPredicate

/**
 * Registry of named condition predicates.
 *
 * Mirrors the [io.karandash.patterns.PatternRegistry] API for consistency.
 * All methods are thread-safe by contract.
 *
 * @param C The context type that registered predicates evaluate against.
 */
public interface PredicateRegistry<C> {
    /**
     * Returns the predicate registered under [name], or `null` if not found.
     */
    public fun get(name: String): ConditionPredicate<C>?

    /**
     * Returns `true` if a predicate with [name] is registered.
     */
    public fun exists(name: String): Boolean

    /**
     * Returns an immutable snapshot of all registered predicates.
     */
    public fun all(): List<ConditionPredicate<C>>

    /**
     * Registers [predicate] under [predicate.name].
     * If a predicate with that name already exists, it is replaced.
     */
    public fun register(predicate: ConditionPredicate<C>)
}
