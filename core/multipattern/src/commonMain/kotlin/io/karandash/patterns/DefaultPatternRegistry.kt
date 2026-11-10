package io.karandash.patterns

import io.karandash.helpers.PatternPrefixes
import io.karandash.helpers.SafeRegexCompiler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Mutable in-memory implementation of [PatternRegistry].
 *
 * Thread-safe via [MutableStateFlow] with immutable map snapshots (copy-on-write).
 */
class DefaultPatternRegistry : PatternRegistry {
    private val definitions = MutableStateFlow(emptyMap<String, PatternDefinition>())

    override fun register(definition: PatternDefinition) {
        definitions.update { it + (definition.name to definition) }
    }

    /**
     * Resolves [name] to its expanded pattern string with [params] applied,
     * or `null` if the name is not registered.
     *
     * Default values declared on each [PatternParam] are merged with [params]
     * before invoking [PatternDefinition.expand].
     */
    override fun resolve(
        name: String,
        params: Map<String, String>,
    ): String? {
        val def = definitions.value[name] ?: return null
        val merged = buildMergedParams(def, params)
        return def.expand(merged)
    }

    override fun exists(name: String): Boolean = name in definitions.value

    override fun all(): List<PatternDefinition> = definitions.value.values.toList()

    /**
     * Validates a pattern string by attempting safe regex compilation.
     *
     * @return `null` if the pattern is valid, or an error message describing the problem.
     */
    override fun validate(pattern: String): String? {
        val stripped = PatternPrefixes.strip(pattern)
        return when (val result = SafeRegexCompiler.Default.compile(stripped)) {
            is SafeRegexCompiler.CompileResult.Success -> {
                null
            }

            is SafeRegexCompiler.CompileResult.TooLong -> {
                "Pattern too long (${result.length} > ${result.maxLength} chars)"
            }

            is SafeRegexCompiler.CompileResult.NestedQuantifiers -> {
                "Pattern has nested quantifiers (depth ${result.depth}) — risk of catastrophic backtracking"
            }

            is SafeRegexCompiler.CompileResult.InvalidPattern -> {
                "Invalid regex pattern: ${result.message}"
            }
        }
    }

    private fun buildMergedParams(
        def: PatternDefinition,
        callerParams: Map<String, String>,
    ): Map<String, String> {
        if (def.params.isEmpty()) return callerParams
        val merged = mutableMapOf<String, String>()
        for (param in def.params) {
            val value = callerParams[param.name] ?: param.defaultValue
            if (value != null) merged[param.name] = value
        }
        // Include any extra caller params not declared in the definition
        merged.putAll(callerParams)
        return merged
    }
}
