package io.karandash.sdk

import io.karandash.api.InternalKarandashApi

/**
 * Optional extension for hosts that need to provide module-specific plugin config.
 *
 * When available, loaders should request a scoped context per module ID before
 * calling Module.initialize().
 */
@InternalKarandashApi
public interface ModuleScopedPluginContextFactory {
    public fun forModule(moduleId: String): PluginContext

    /**
     * Create a context scoped to the concrete [module] instance.
     *
     * Default implementation falls back to [forModule] by ID. Hosts can override this
     * to include module-specific policies (for example, per-module env allowlists).
     */
    public fun forModule(module: Module): PluginContext = forModule(module.id)
}
