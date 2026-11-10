package io.karandash.cli.provider

import io.karandash.api.ModuleRegistry
import io.karandash.api.errors.KarandashError
import io.karandash.api.errors.ProviderException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.bootstrap.ModuleSelector
import io.karandash.utils.SuggestionEngine

/**
 * Resolves platform names to [PlatformServiceModule] instances.
 *
 * Built-in platform services are expected to be registered in the supplied [ModuleRegistry]
 * by the time [resolve] is called (loaded via [io.karandash.bootstrap.BuiltInPluginLoader]).
 */
class ProviderResolver {
    /**
     * Resolve a platform name to a configured [PlatformServiceModule].
     *
     * @param platform Platform name (case-insensitive): gitlab, github, bitbucket, etc.
     * @param registry Module registry containing all loaded platform services (built-in + external).
     * @param preferredPluginIds Preferred dynamic module IDs (from config.plugins.enabled).
     * @return Configured [PlatformServiceModule].
     * @throws ProviderException if platform is unknown.
     */
    fun resolve(
        platform: String,
        registry: ModuleRegistry,
        preferredPluginIds: Set<String> = emptySet()
    ): PlatformServiceModule {
        val registryProviders = registry.platformServices()

        val knownProviders = registryProviders
            .map { it.id }
            .distinct()
            .sorted()

        val trimmedPlatform = platform.trim()
        if (trimmedPlatform.isEmpty()) {
            throw ProviderException(
                "${unknownPlatform(platform)} Supported platforms: ${knownProviders.joinToString(", ")}"
            )
        }

        val resolvedPlatform = registry.findByAlias(trimmedPlatform)?.id ?: trimmedPlatform
        val normalizedPreferredIds = preferredPluginIds
            .mapTo(linkedSetOf()) { registry.findByAlias(it)?.id ?: it }

        val selection = ModuleSelector.selectByHint(
            modules = registryProviders,
            hint = resolvedPlatform,
            preferredIds = normalizedPreferredIds,
            typeName = "provider modules",
            allowFuzzy = false
        )
        selection.error?.let { throw ProviderException(it) }
        selection.module?.let { return it }

        if (registryProviders.isNotEmpty()) {
            val fuzzySelection = ModuleSelector.selectByHint(
                modules = registryProviders,
                hint = resolvedPlatform,
                preferredIds = normalizedPreferredIds,
                typeName = "provider modules",
                allowFuzzy = true
            )
            fuzzySelection.error?.let { throw ProviderException(it) }
            fuzzySelection.module?.let { return it }
        }

        val aliasedProvider = registry.findByAlias(trimmedPlatform)
        if (aliasedProvider != null) {
            throw ProviderException("Provider '${aliasedProvider.id}' is not loaded in the registry")
        }

        val suggestions = SuggestionEngine.suggest(resolvedPlatform, knownProviders)
        val errorMessage = if (suggestions.isNotEmpty()) {
            "${unknownPlatform(platform)} Did you mean: ${suggestions.joinToString(", ")}?"
        } else {
            "${unknownPlatform(platform)} Supported platforms: ${knownProviders.joinToString(", ")}"
        }
        throw ProviderException(errorMessage)
    }

    private fun unknownPlatform(platform: String): String = "${KarandashError.UNKNOWN_PLATFORM.bracketCode} Unknown platform: $platform."
}
