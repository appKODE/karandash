package io.karandash.api.platform

/**
 * Defines a named pattern that can be referenced as `@name` in config.
 *
 * Not `@Serializable` - [expand] is a lambda and cannot be serialized.
 *
 * @param name Identifier used in `@name` references.
 * @param description Human-readable summary for IDE completions and docs.
 * @param params Declared parameters accepted by this pattern.
 * @param examples Strings the expanded regex is expected to match (for docs and testing).
 * @param counterExamples Strings the expanded regex is expected to reject (for docs and testing).
 * @param expand Produces the concrete pattern string for given resolved parameter values.
 */
public data class PatternDefinition(
    val name: String,
    val description: String = "",
    val params: List<PatternParam> = emptyList(),
    val examples: List<String> = emptyList(),
    val counterExamples: List<String> = emptyList(),
    val expand: (params: Map<String, String>) -> String,
)
