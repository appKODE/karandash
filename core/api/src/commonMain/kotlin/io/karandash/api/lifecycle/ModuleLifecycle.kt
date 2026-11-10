package io.karandash.api.lifecycle

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.PluginContext

/**
 * Owner of module initialization and teardown.
 *
 * The host implementation, [ModuleLifecycleManager], is public only so loaders in other modules can
 * share it, and is marked [io.karandash.api.InternalKarandashApi]: plugins code against this
 * contract, not against it. Implementations must shut
 * tracked modules down in reverse initialization order, bound each `shutdown()` with a timeout
 * that does not cancel the remaining shutdowns, and be safe to call concurrently.
 */
@ExperimentalKarandashApi
public interface ModuleLifecycle {
    /** Initializes [module] with [context] and tracks it when initialization succeeds. */
    public suspend fun initialize(
        module: Module,
        context: PluginContext,
    ): Boolean

    /** Tracks a module that was initialized elsewhere so that [shutdownAll] covers it. */
    public suspend fun track(module: Module)

    /** Stops tracking the module with [moduleId]; it will not be shut down by [shutdownAll]. */
    public suspend fun untrack(moduleId: String)

    /** Shuts down every tracked module in reverse order. Never throws. */
    public suspend fun shutdownAll()
}
