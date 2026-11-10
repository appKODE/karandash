package io.karandash.loader.wasm

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.LoadResult
import io.karandash.loader.NativeAbi
import io.karandash.loader.PluginDiscovery
import io.karandash.loader.PluginLoader
import io.karandash.loader.apiLevelIncompatibility
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.platform.readBytes
import io.karandash.platform.safeName
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import io.karandash.sdk.NoOpLogger
import io.karandash.wasm.createWasmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * Loads WASM plugin modules (.wasm files) using Chicory (JVM) or Chasm (native).
 *
 * Each .wasm file is loaded into its own isolated engine instance.
 * The ABI version is verified before creating a [WasmModuleBridge].
 */
@OptIn(InternalKarandashApi::class)
class WasmPluginLoader(
    private val registry: ModuleRegistry,
    private val logger: Logger = NoOpLogger,
    private val guestCallTimeout: Duration = GuestCallTimeout.fromEnvironment(logger),
) : PluginLoader {
    private val lifecycle = ModuleLifecycleManager(logger)

    override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext,
    ): LoadResult {
        val wasmFiles = PluginDiscovery.discoverWasmModules(directories)
        return loadFiles(wasmFiles, context)
    }

    @Suppress("TooGenericExceptionCaught") // WASM runtime boundary: thrown types are unpredictable
    private suspend fun loadFiles(
        files: List<Path>,
        context: PluginContext,
    ): LoadResult {
        val loaded = mutableListOf<String>()
        val sources = mutableMapOf<String, String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        for (file in files) {
            try {
                val wasmBytes = withContext(Dispatchers.IO) { file.readBytes() }
                // one host channel per guest; host services run on the context the guest was initialized with
                val current = MutableStateFlow<WasmModuleBridge?>(null)
                val host = HostServiceDispatcher({ current.value?.id ?: file.safeName }, logger) {
                    current.value?.initContext?.value ?: context
                }
                val engine = newEngine(wasmBytes, host)

                val abiVersion = try {
                    engine.callI32(WasmAbi.ABI_VERSION)
                } catch (e: Exception) {
                    // boundary: runs a guest WASM module
                    e.rethrowCancellation()
                    logger.debug("WASM ABI check failed for ${file.safeName}: ${e.message}")
                    engine.close()
                    failed.add(
                        LoadResult.LoadFailure(
                            file.safeName,
                            "Missing karandash_abi_version: ${e.message}",
                        )
                    )
                    continue
                }
                if (!NativeAbi.isSupported(abiVersion)) {
                    engine.close()
                    failed.add(LoadResult.LoadFailure(file.safeName, NativeAbi.unsupportedMessage(abiVersion)))
                    continue
                }

                val bridge = try {
                    WasmModuleBridge(engine, logger, WasmExportNames.of(wasmBytes), WasmExportNames.paramCounts(wasmBytes), host) {
                        newEngine(wasmBytes, host)
                    }.also { current.value = it }
                } catch (e: IllegalArgumentException) {
                    // A timed-out metadata export reads as blank, which then fails type parsing.
                    if (!engine.isAbandoned) throw e
                    null
                }
                if (bridge == null || engine.isAbandoned) {
                    bridge?.shutdown() ?: engine.close()
                    failed.add(LoadResult.LoadFailure(file.safeName, "Guest call timed out after $guestCallTimeout"))
                    continue
                }
                val parts = bridge.asRegisteredModules()
                val module = parts.first()
                val apiReason = apiLevelIncompatibility(module)
                if (apiReason != null) {
                    failed.add(LoadResult.LoadFailure(file.safeName, apiReason))
                    bridge.shutdown()
                    continue
                }
                if (registry.get(module.id) != null) {
                    bridge.shutdown()
                    skipped.add(module.id)
                    continue
                }

                // the guest is initialized once at bootstrap when any of its parts needs it (CLI command, config format)
                val bootPart = parts.firstOrNull { it.shouldInitializeDuringBootstrap() }
                if (bootPart == null || bootPart.initialize(contextForModule(context, bootPart))) {
                    registry.registerComposite(parts)
                    lifecycle.track(bridge)
                    loaded.add(module.id)
                    sources[module.id] = file.toString()
                } else {
                    bridge.shutdown()
                    skipped.add(module.id)
                }
            } catch (e: Exception) {
                // boundary: runs a guest WASM module
                e.rethrowCancellation()
                failed.add(LoadResult.LoadFailure(file.safeName, "Load failed: ${e.message}"))
            }
        }

        return LoadResult(loaded, failed, skipped, sources)
    }

    private fun newEngine(
        wasmBytes: ByteArray,
        host: HostServiceDispatcher,
    ) = createWasmEngine(wasmBytes, guestCallTimeout, host::call, WasmTimeoutWatchdog(logger)::onTimeout)

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

    private fun contextForModule(
        context: PluginContext,
        module: Module,
    ): PluginContext {
        val factory = context as? ModuleScopedPluginContextFactory
        return factory?.forModule(module) ?: factory?.forModule(module.id) ?: context
    }
}
