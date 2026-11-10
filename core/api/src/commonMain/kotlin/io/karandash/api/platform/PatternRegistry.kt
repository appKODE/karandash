package io.karandash.api.platform

/**
 * Registry of named patterns referenced via the `@name` prefix in config.
 *
 * Implementations are responsible for built-in and user-registered patterns.
 * All methods are thread-safe by contract.
 */
public interface PatternRegistry {
    /**
     * Resolves a named pattern by [name] with the given [params] and returns
     * the expanded pattern string, or `null` if the name is not registered.
     *
     * @param name Pattern name without the leading `@`.
     * @param params Key-value pairs provided by the caller; defaults from [PatternDefinition.params] are applied.
     */
    public fun resolve(
        name: String,
        params: Map<String, String> = emptyMap()
    ): String?

    /**
     * Returns `true` if [name] is registered.
     *
     * @param name Pattern name without the leading `@`.
     */
    public fun exists(name: String): Boolean

    /**
     * Registers [definition] under [definition.name].
     * If a pattern with that name already exists it is replaced.
     */
    public fun register(definition: PatternDefinition)

    /** Returns an immutable snapshot of all registered [PatternDefinition]s. */
    public fun all(): List<PatternDefinition>

    /**
     * Validates a pattern string, returning `null` if valid or an error message.
     * Checks syntax and regex validity.
     *
     * Default implementation accepts all patterns.
     */
    public fun validate(pattern: String): String? = null
}
