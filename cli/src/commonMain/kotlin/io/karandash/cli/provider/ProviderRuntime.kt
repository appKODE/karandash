package io.karandash.cli.provider

import io.karandash.api.ModuleRegistry
import io.karandash.api.module.PlatformServiceModule

/**
 * Facade for provider runtime concerns:
 * - provider module resolution (requires a populated [ModuleRegistry])
 * - provider auth diagnostics
 * - built-in provider module config assembly
 */
class ProviderRuntime(
    private val resolver: ProviderResolver
) {
    fun resolveModule(
        platform: String,
        registry: ModuleRegistry,
        preferredPluginIds: Set<String> = emptySet()
    ): PlatformServiceModule =
        resolver.resolve(
            platform = platform,
            registry = registry,
            preferredPluginIds = preferredPluginIds
        )
}
