package io.karandash.cli.commands.validate

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.errors.ProviderException
import io.karandash.bootstrap.ModuleSelector

/**
 * Shared dynamic module lookup helper for validate runtime components.
 */
internal object DynamicModuleSelector {
    inline fun <reified T> resolve(
        registry: ModuleRegistry,
        type: ModuleType,
        platformName: String?,
        preferredPluginIds: Set<String>,
        typeName: String = "dynamic ${type.name.lowercase()} modules"
    ): T? where T : Module {
        val modules = registry.getByType(type).filterIsInstance<T>()
        val selection = ModuleSelector.selectByHint(
            modules = modules,
            hint = platformName,
            preferredIds = preferredPluginIds,
            typeName = typeName
        )
        selection.error?.let { throw ProviderException(it) }
        return selection.module
    }
}
