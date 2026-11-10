@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, InternalKarandashApi::class)

package io.karandash.loader.native

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.exception.PluginLoadException
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.LoadResult
import io.karandash.loader.NativeAbi
import io.karandash.loader.PluginLoader
import io.karandash.loader.apiLevelIncompatibility
import io.karandash.loader.bridge.TransportModuleBridge
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.closeNativeLibrary
import io.karandash.platform.exists
import io.karandash.platform.extension
import io.karandash.platform.findNativeSymbol
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.nativeLibraryError
import io.karandash.platform.openNativeLibrary
import io.karandash.platform.safeName
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.io.files.Path
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration

/**
 * Loads native plugins (.so, .dylib, .dll) via platform-specific dynamic loader API.
 * Validates ABI version and registers each plugin's [TransportModuleBridge] parts as one composite entry.
 *
 * **Platform Support:**
 * - macOS: .dylib files
 * - Linux: .so files
 * - Windows: .dll files
 */
actual class NativePluginLoader actual constructor(
    private val registry: ModuleRegistry,
    private val logger: Logger,
) : PluginLoader {
    private val lifecycle = ModuleLifecycleManager(logger)
    private val guestCallTimeout by lazy { GuestCallTimeout.fromEnvironment(logger) }

    /** Open bridges by module ID; their library handles close on unload, not on shutdown(). */
    private val bridges = MutableStateFlow<Map<String, TransportModuleBridge>>(emptyMap())

    actual override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult {
        val nativeLibs = discoverNativeLibs(directories)
        return loadLibraries(nativeLibs, context)
    }

    private fun discoverNativeLibs(directories: List<Path>): List<Path> {
        val extensions = when (Platform.osFamily) {
            OsFamily.MACOSX -> setOf("dylib")
            OsFamily.LINUX -> setOf("so")
            OsFamily.WINDOWS -> setOf("dll")
            else -> emptySet()
        }

        return directories
            .filter { it.exists() && it.isDirectory() }
            .flatMap { dir ->
                dir.listFiles()?.filter { file ->
                    file.extension() in extensions
                } ?: emptyList()
            }.sortedBy { it.safeName }
    }

    @Suppress("TooGenericExceptionCaught") // Native plugin loading boundary
    private suspend fun loadLibraries(
        libFiles: List<Path>,
        context: PluginContext
    ): LoadResult {
        val loaded = mutableListOf<String>()
        val sources = mutableMapOf<String, String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        for (libFile in libFiles) {
            try {
                val libPath = libFile.absolutePathOrSelf().toString()
                val handle = openNativeLibrary(libPath)
                if (handle == null) {
                    val error = nativeLibraryError()
                    failed.add(LoadResult.LoadFailure(libFile.safeName, "Failed to load library: $error"))
                    continue
                }

                val abiVersion = getAbiVersion(handle)
                if (!NativeAbi.isSupported(abiVersion)) {
                    failed.add(LoadResult.LoadFailure(libFile.safeName, NativeAbi.unsupportedMessage(abiVersion)))
                    closeNativeLibrary(handle)
                    continue
                }

                val bridge = NativeModuleBridge.create(handle, logger, guestCallTimeout, context)
                val parts = bridge.asRegisteredModules()
                val module = parts.first()

                val apiReason = apiLevelIncompatibility(module)
                if (apiReason != null) {
                    failed.add(LoadResult.LoadFailure(libFile.safeName, apiReason))
                    bridge.close()
                    continue
                }

                if (registry.get(module.id) != null) {
                    skipped.add(libFile.safeName)
                    bridge.close()
                    continue
                }

                // the guest is initialized once at bootstrap when any of its parts needs it (CLI command, config format)
                val bootPart = parts.firstOrNull { it.shouldInitializeDuringBootstrap() }
                if (bootPart == null || bootPart.initialize(contextForModule(context, bootPart))) {
                    registry.registerComposite(parts)
                    lifecycle.track(bridge)
                    bridges.update { it + (module.id to bridge) }
                    loaded.add(module.id)
                    sources[module.id] = libFile.toString()
                } else {
                    skipped.add(module.id)
                    bridge.close()
                }
            } catch (e: Exception) {
                // boundary: loads a third-party native plugin
                e.rethrowCancellation()
                logger.debug("Native plugin load failed for ${libFile.safeName}: ${e.message}")
                failed.add(LoadResult.LoadFailure(libFile.safeName, "Load failed: ${e.message}"))
            }
        }

        return LoadResult(loaded, failed, skipped, sources)
    }

    private fun getAbiVersion(handle: COpaquePointer): Int {
        val fn = findNativeSymbol(handle, "karandash_abi_version")
            ?.reinterpret<CFunction<() -> Int>>()
            ?: throw PluginLoadException("Missing function: karandash_abi_version")

        return fn.invoke()
    }

    actual override suspend fun unload(moduleId: String): Boolean {
        if (!lifecycle.shutdown(moduleId)) return false
        bridges.getAndUpdate { it - moduleId }[moduleId]?.close()
        registry.unregister(moduleId)
        return true
    }

    actual override suspend fun unloadAll(shutdownTimeout: Duration) {
        val ids = lifecycle.trackedIds
        lifecycle.shutdownAll(shutdownTimeout)
        bridges.getAndUpdate { emptyMap() }.values.forEach { it.close() }
        ids.forEach { registry.unregister(it) }
    }

    private fun contextForModule(
        context: PluginContext,
        module: Module
    ): PluginContext {
        val factory = context as? ModuleScopedPluginContextFactory
        return factory?.forModule(module) ?: factory?.forModule(module.id) ?: context
    }
}
