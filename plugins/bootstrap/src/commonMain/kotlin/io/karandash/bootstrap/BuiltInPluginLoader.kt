package io.karandash.bootstrap

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleResult
import io.karandash.api.PluginContext
import io.karandash.api.errors.KarandashError
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginLoader
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * Loads built-in modules bundled with the CLI into the [ModuleRegistry].
 *
 * Built-in modules always run unconditionally - no `manifest.yaml` declaration is needed
 * or consulted for them. This loader runs first in the bootstrap sequence, before
 * any directory scan for external plugins.
 *
 * Uses a compiled-in explicit factory list so the same set of modules is loaded on every
 * platform (JVM, macOS, Linux, Windows). External third-party plugins still use their
 * respective loaders (JAR/ServiceLoader, JS/QuickJS, WASM, native/dlopen).
 *
 * Bootstrap eagerly initializes only modules needed before dispatch:
 * - [io.karandash.api.module.ConfigFormatModule] (config parsing/discovery)
 * - [io.karandash.api.module.CliCommandModule] (command registration)
 *
 * All other modules are registered during bootstrap and initialized later
 * at runtime with selected config + env scope.
 *
 * Modules whose [Module.initialize] returns false are silently
 * skipped rather than reported as failures - this is the expected path for
 * optional modules (e.g. AI rules) when the required env vars are absent.
 *
 * Every registered module (initialized or deferred) is tracked by a [ModuleLifecycleManager],
 * which shuts them down in reverse order with per-module failure isolation and timeout.
 */
@OptIn(InternalKarandashApi::class)
class BuiltInPluginLoader internal constructor(
    private val registry: ModuleRegistry,
    private val logger: Logger,
    private val factories: List<(ModuleRegistry) -> Module>,
) : PluginLoader {
    constructor(
        registry: ModuleRegistry,
        logger: Logger = NoOpLogger,
    ) : this(registry, logger, builtInFactories)

    private val lifecycle = ModuleLifecycleManager(logger)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext,
    ): LoadResult {
        val loaded = mutableListOf<String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        logger.debug("Loading ${factories.size} built-in modules...")
        for (factory in factories) {
            try {
                loadSingleModule(factory, context, loaded, failed, skipped)
            } catch (e: Throwable) {
                // boundary: module factory/initialize is plugin code
                e.rethrowCancellation()
                failed += LoadResult.LoadFailure(
                    "<unknown>",
                    "unexpected ${e::class.simpleName ?: "error"}: ${e.message}",
                )
            }
        }

        logger.debug("Built-in loading: ${loaded.size} loaded, ${skipped.size} skipped, ${failed.size} failed")
        return LoadResult(loaded = loaded, failed = failed, skipped = skipped)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadSingleModule(
        factory: (ModuleRegistry) -> Module,
        context: PluginContext,
        loaded: MutableList<String>,
        failed: MutableList<LoadResult.LoadFailure>,
        skipped: MutableList<String>,
    ) {
        val module = try {
            factory(registry)
        } catch (e: Throwable) {
            // boundary: module factory/initialize is plugin code
            e.rethrowCancellation()
            failed += LoadResult.LoadFailure("<unknown>", "factory error: ${e.message}")
            return
        }

        if (registry.get(module.id) != null) {
            logger.debug("Module '${module.id}': skipped (already registered)")
            skipped += module.id
            return
        }

        if (!module.shouldInitializeDuringBootstrap()) {
            registry.register(module)
            lifecycle.track(module)
            logger.debug("Module '${module.id}': registered (deferred init)")
            loaded += module.id
            return
        }

        val moduleContext = (context as? ModuleScopedPluginContextFactory)
            ?.forModule(module)
            ?: context

        when (val result = lifecycle.initializeResult(module, moduleContext)) {
            is ModuleResult.Failure -> {
                failed += LoadResult.LoadFailure(module.id, "${KarandashError.PLUGIN_INIT_FAILED.bracketCode} ${result.errorMessage}")
                return
            }

            is ModuleResult.Success -> {
                if (!result.value) {
                    logger.debug("Module '${module.id}': skipped (initialize returned false)")
                    skipped += module.id
                    return
                }
            }
        }

        registry.register(module)
        logger.debug("Module '${module.id}': loaded and initialized")
        loaded += module.id
    }

    /** Whether [module] is the instance this loader registered (a guest reusing a built-in ID is not). */
    fun isBuiltIn(module: Module): Boolean = lifecycle.isTracked(module)

    override suspend fun unload(moduleId: String): Boolean {
        if (!lifecycle.shutdown(moduleId)) return false
        registry.unregister(moduleId)
        return true
    }

    override suspend fun unloadAll(shutdownTimeout: Duration) {
        val ids = lifecycle.trackedIds
        lifecycle.shutdownAll(shutdownTimeout)
        ids.forEach { registry.unregister(it) }
    }

    companion object {
        /**
         * Factory list generated from `plugins/builtin/builtin-modules.txt` at build time.
         *
         * @see io.karandash.builtin.BuiltInModuleRegistry
         */
        private val builtInFactories: List<(ModuleRegistry) -> Module> = io.karandash.builtin.BuiltInModuleRegistry.factories
    }
}
