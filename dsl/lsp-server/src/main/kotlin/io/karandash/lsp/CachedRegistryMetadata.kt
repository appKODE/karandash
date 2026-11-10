package io.karandash.lsp

import io.karandash.api.ModuleRegistry
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import kotlinx.coroutines.runBlocking

/**
 * Caches module metadata from the [ModuleRegistry] for O(1) lookups.
 *
 * All suspend-based module queries are resolved once at construction
 * via [runBlocking] (safe: LSP server is JVM-only, dispatched on LSP4J thread pool).
 * The [ModuleRegistry] is not queried again after construction.
 */
class CachedRegistryMetadata(
    registry: ModuleRegistry
) {
    /** Rule IDs known to the registry. */
    val ruleIds: Set<String> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        registry
            .ruleModules()
            .flatMap { it.contributedRules() }
            .toSet()
    }

    /** Provider DSL names known to the registry. */
    val providerIds: Set<String> =
        registry.platformServices().map { it.dslName }.toSet()

    /** Reporter format IDs for block-style reporters (non-empty configSchema). */
    val blockReporterIds: Set<String> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        registry
            .reporters()
            .filter { it.configSchema().isNotEmpty() }
            .map { it.formatId }
            .toSet()
    }

    /** Reporter format IDs for property-style reporters (empty configSchema). */
    val propertyReporterIds: Set<String> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        registry
            .reporters()
            .filter { it.configSchema().isEmpty() }
            .map { it.formatId }
            .toSet()
    }

    /** Config property names per rule ID. */
    val ruleConfigKeys: Map<String, Set<String>> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        buildMap {
            for (module in registry.ruleModules()) {
                val keys = module.configSchema().map { it.name }.toSet()
                for (ruleId in module.contributedRules()) {
                    put(ruleId, keys)
                }
            }
        }
    }

    /** Config property type lookup: (ruleId, propertyName) -> type. */
    val rulePropertyTypes: Map<Pair<String, String>, ConfigPropertyType> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        buildMap {
            for (module in registry.ruleModules()) {
                for (prop in module.configSchema()) {
                    for (ruleId in module.contributedRules()) {
                        put(ruleId to prop.name, prop.type)
                    }
                }
            }
        }
    }

    /** Config properties per rule ID (for hover documentation). */
    val ruleConfigProperties: Map<String, List<ConfigProperty>> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        buildMap {
            for (module in registry.ruleModules()) {
                val props = module.configSchema()
                for (ruleId in module.contributedRules()) {
                    put(ruleId, props)
                }
            }
        }
    }

    /** Module hover docs keyed by rule ID. */
    val moduleHoverDocs: Map<String, String> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        buildMap {
            for (module in registry.ruleModules()) {
                val doc = module.hoverDocumentation ?: continue
                for (ruleId in module.contributedRules()) {
                    put(ruleId, doc)
                }
            }
        }
    }

    /** `reporters { }` hover docs keyed by format ID: format modules (the built-in ones) and reporter modules. */
    val reporterHoverDocs: Map<String, String> =
        registry.formats().mapNotNull { f -> f.hoverDocumentation?.let { f.formatId to it } }.toMap() +
            registry.reporters().mapNotNull { r -> r.hoverDocumentation?.let { r.formatId to it } }

    /** Provider hover docs keyed by DSL name. */
    val providerHoverDocs: Map<String, String> =
        registry
            .platformServices()
            .mapNotNull { p ->
                p.hoverDocumentation?.let { p.dslName to it }
            }.toMap()

    /** Rule descriptions: (ruleId, description). */
    val ruleDescriptions: List<Pair<String, String>> = runBlocking {
        // bridge-boundary: LSP4J handlers are synchronous; computed once at startup
        registry.ruleModules().flatMap { module ->
            module.contributedRules().map { ruleId -> ruleId to module.description }
        }
    }

    /** Provider descriptions: (dslName, description). */
    val providerDescriptions: List<Pair<String, String>> =
        registry.platformServices().map { it.dslName to it.description }
}
