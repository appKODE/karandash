package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.patterns.CorePatternRegistry
import io.karandash.patterns.DefaultPatternRegistry
import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.PatternDefinition
import io.karandash.patterns.PatternResolver

/** The config's `patterns:` block as `validate` and `config validate` both check it. */
internal object ConfigPatterns {
    /** Core built-ins, every registered module's patterns, then the config's own [patterns]. */
    fun registry(
        patterns: Map<String, String>,
        modules: ModuleRegistry,
    ): DefaultPatternRegistry {
        val registry = CorePatternRegistry.create()
        for (moduleId in modules.registeredIds()) {
            modules.get(moduleId)?.registerPatterns(registry)
        }
        patterns.forEach { (name, patternString) ->
            registry.register(
                PatternDefinition(
                    name = name,
                    description = "User-defined pattern from config",
                    params = emptyList(),
                    expand = { patternString },
                ),
            )
        }
        return registry
    }

    /** `patterns.<name>: <reason>` for every config pattern [resolver] cannot compile (nested quantifiers included). */
    fun errors(
        patterns: Map<String, String>,
        resolver: PatternResolver,
    ): List<String> =
        patterns.keys.mapNotNull { name ->
            (resolver.resolve("@$name") as? PatternCompilationResult.Error)?.let { "patterns.$name: ${it.message}" }
        }
}
