package io.karandash.loader

import io.karandash.api.PluginContext
import io.karandash.api.lifecycle.ModuleLifecycleManager
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * Common interface for all plugin loaders (JAR, Native, JS).
 * Each loader is responsible for discovering, loading, and managing
 * plugins in a specific format.
 */
interface PluginLoader {
    /**
     * Load all plugins from the given directories.
     *
     * @param directories Directories to scan for plugins
     * @param context Plugin context passed to initialize() method
     * @return Load result with loaded, failed, and skipped plugins
     */
    suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult

    /**
     * Unload a plugin by module ID.
     *
     * @param moduleId The module ID to unload
     * @return true if the module was found and unloaded, false otherwise
     */
    suspend fun unload(moduleId: String): Boolean

    /**
     * Unload all plugins managed by this loader, bounding each module's `shutdown()` by [shutdownTimeout]
     * (`timeouts.pluginShutdown`).
     */
    suspend fun unloadAll(shutdownTimeout: Duration = ModuleLifecycleManager.DEFAULT_SHUTDOWN_TIMEOUT)
}
