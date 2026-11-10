package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginDiscovery
import io.karandash.loader.PluginLoader
import io.karandash.loader.apiLevelIncompatibility
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.platform.readText
import io.karandash.platform.safeName
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * Loads JavaScript plugins (.js, .mjs) via QuickJS.
 * Each plugin runs in its own isolated QuickJs engine instance.
 *
 * Discovers JS files using [PluginDiscovery], reads source via
 * [readText], injects SDK bindings with [JsContextInjector], and
 * creates a [JsModuleBridge] to expose the plugin to the engine.
 */
@OptIn(InternalKarandashApi::class)
class JsPluginLoader(
    private val registry: ModuleRegistry,
    private val logger: Logger = NoOpLogger,
    private val guestCallTimeout: Duration = GuestCallTimeout.fromEnvironment(logger),
) : PluginLoader {
    private val mutex = Mutex()
    private val engines = MutableStateFlow<Map<String, Pair<QuickJs, JsCallGuard>>>(emptyMap())
    private val lifecycle = ModuleLifecycleManager(logger)

    @Suppress("TooGenericExceptionCaught") // JS plugin loading boundary
    override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult {
        val jsFiles = PluginDiscovery.discoverJsModules(directories)
        val loaded = mutableListOf<String>()
        val sources = mutableMapOf<String, String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        for (file in jsFiles) {
            var engine: QuickJs? = null
            // the bindings follow the context of the guest's latest initialize, the load context until then
            val current = MutableStateFlow<JsModuleBridge?>(null)
            val runContext = {
                current.value
                    ?.transport
                    ?.storedContext
                    ?.value ?: context
            }
            val host = HostServiceDispatcher({ current.value?.id ?: file.safeName }, logger, runContext)
            // the guard interrupts a guest spinning past its deadline, so the engine survives it (JsCallGuard)
            val guard = JsCallGuard(guestCallTimeout, logger, host) { engine?.interruptEvaluation() }
            try {
                engine = QuickJs.create(Dispatchers.Default)

                val source = withContext(Dispatchers.IO) { file.readText() }

                JsContextInjector(engine, runContext, host).inject()

                val bridge = guard.run("load ${file.safeName}") {
                    JsModuleBridge.fromSource(engine, source, file.toString(), guard)
                }
                current.value = bridge
                val parts = bridge.asRegisteredModules()
                val module = parts.first()

                val apiReason = apiLevelIncompatibility(module)
                if (apiReason != null) {
                    failed.add(LoadResult.LoadFailure(file.safeName, apiReason))
                    closeEngine(engine, guard)
                    continue
                }

                if (registry.get(module.id) != null) {
                    skipped.add(module.id)
                    closeEngine(engine, guard)
                    continue
                }

                // the guest is initialized once at bootstrap when any of its parts needs it (CLI command, config format)
                val bootPart = parts.firstOrNull { it.shouldInitializeDuringBootstrap() }
                if (bootPart == null || bootPart.initialize(contextForModule(context, bootPart))) {
                    registry.registerComposite(parts)
                    mutex.withLock { engines.update { it + (module.id to (engine to guard)) } }
                    lifecycle.track(bridge)
                    loaded.add(module.id)
                    sources[module.id] = file.toString()
                } else {
                    skipped.add(module.id)
                    closeEngine(engine, guard)
                }
            } catch (e: Exception) {
                // boundary: loads and runs a guest JS plugin
                e.rethrowCancellation()
                failed.add(LoadResult.LoadFailure(file.safeName, "Load failed: ${e.message}"))
                engine?.let { closeEngine(it, guard) }
            }
        }

        return LoadResult(loaded, failed, skipped, sources)
    }

    override suspend fun unload(moduleId: String): Boolean {
        if (!lifecycle.shutdown(moduleId)) return false
        registry.unregister(moduleId)
        mutex.withLock { engines.value[moduleId].also { engines.update { it - moduleId } } }?.let { (engine, guard) ->
            closeEngine(engine, guard)
        }
        return true
    }

    override suspend fun unloadAll(shutdownTimeout: Duration) {
        val ids = lifecycle.trackedIds
        lifecycle.shutdownAll(shutdownTimeout)
        ids.forEach { registry.unregister(it) }
        val engineSnapshot = mutex.withLock { engines.value.values.also { engines.value = emptyMap() } }
        engineSnapshot.forEach { (engine, guard) -> closeEngine(engine, guard) }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: QuickJS close may fail with any runtime error
    private fun closeEngine(
        engine: QuickJs,
        guard: JsCallGuard,
    ) {
        // A timed-out call is still executing inside this engine; freeing it would crash the host.
        if (guard.isHung) return
        try {
            engine.close()
        } catch (e: Exception) {
            // boundary: loads and runs a guest JS plugin
            e.rethrowCancellation()
            logger.warn("JS engine close failed: ${e.message}")
        }
    }

    private fun contextForModule(
        context: PluginContext,
        module: Module
    ): PluginContext {
        val factory = context as? ModuleScopedPluginContextFactory
        return factory?.forModule(module) ?: factory?.forModule(module.id) ?: context
    }
}
