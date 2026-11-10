package io.karandash.loader.native

import com.sun.jna.Library
import com.sun.jna.NativeLibrary
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
import io.karandash.loader.bridge.JnaTransport
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import kotlinx.io.files.Path
import java.io.File
import kotlin.time.Duration

/**
 * Loads native plugins (.so, .dylib, .dll) via JNA.
 * Validates ABI version and creates NativeModuleBridge for each plugin.
 *
 * **Note on Native Library Lifecycle:**
 * JNA does not provide a reliable mechanism to unload native libraries once loaded.
 * Libraries remain mapped in the process address space for the process lifetime.
 * On ABI version mismatch or duplicate detection, the library remains loaded but
 * no bridge is created. This is acceptable for production use as plugin loading
 * typically occurs once at startup.
 */
@OptIn(InternalKarandashApi::class)
actual class NativePluginLoader actual constructor(
    private val registry: ModuleRegistry,
    private val logger: Logger,
) : PluginLoader {
    private val lifecycle = ModuleLifecycleManager(logger)
    private val guestCallTimeout by lazy { GuestCallTimeout.fromEnvironment(logger) }

    actual override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult {
        val nativeLibs = PluginDiscovery.discoverNativeLibs(directories)
        val javaFiles = nativeLibs.map { File(it.toString()) }
        return loadLibraries(javaFiles, context)
    }

    @Suppress("TooGenericExceptionCaught") // Native plugin loading boundary
    private suspend fun loadLibraries(
        libFiles: List<File>,
        context: PluginContext
    ): LoadResult {
        val loaded = mutableListOf<String>()
        val sources = mutableMapOf<String, String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        for (libFile in libFiles) {
            try {
                val symbols = NativeLibrary.getInstance(libFile.absolutePath, JNA_OPTIONS)

                val abiVersion = symbols.getFunction(ABI_VERSION_SYMBOL).invokeInt(emptyArray())
                if (!NativeAbi.isSupported(abiVersion)) {
                    failed.add(LoadResult.LoadFailure(libFile.name, NativeAbi.unsupportedMessage(abiVersion)))
                    continue
                }

                val bridge = NativeModuleBridge.create(
                    JnaTransport.callsInto(symbols),
                    logger,
                    guestCallTimeout,
                    symbols,
                    context
                ) { symbol -> hasSymbol(symbols, symbol) }
                val parts = bridge.asRegisteredModules()
                val module = parts.first()

                val apiReason = apiLevelIncompatibility(module)
                if (apiReason != null) {
                    failed.add(LoadResult.LoadFailure(libFile.name, apiReason))
                    continue
                }

                if (registry.get(module.id) != null) {
                    skipped.add(libFile.name)
                    continue
                }

                // the guest is initialized once at bootstrap when any of its parts needs it (CLI command, config format)
                val bootPart = parts.firstOrNull { it.shouldInitializeDuringBootstrap() }
                if (bootPart == null || bootPart.initialize(contextForModule(context, bootPart))) {
                    registry.registerComposite(parts)
                    lifecycle.track(bridge)
                    loaded.add(module.id)
                    sources[module.id] = libFile.toString()
                } else {
                    skipped.add(module.id)
                }
            } catch (e: UnsatisfiedLinkError) {
                failed.add(LoadResult.LoadFailure(libFile.name, "Missing symbols: ${e.message}"))
            } catch (e: Exception) {
                // boundary: loads a third-party native plugin
                e.rethrowCancellation()
                logger.debug("Native plugin load failed for ${libFile.name}: ${e.message}")
                failed.add(LoadResult.LoadFailure(libFile.name, "Load failed: ${e.message}"))
            }
        }

        return LoadResult(loaded, failed, skipped, sources)
    }

    actual override suspend fun unload(moduleId: String): Boolean {
        if (!lifecycle.shutdown(moduleId)) return false
        registry.unregister(moduleId)
        return true
    }

    actual override suspend fun unloadAll(shutdownTimeout: Duration) {
        val ids = lifecycle.trackedIds
        lifecycle.shutdownAll(shutdownTimeout)
        ids.forEach { registry.unregister(it) }
    }

    private fun contextForModule(
        context: PluginContext,
        module: Module
    ): PluginContext {
        val factory = context as? ModuleScopedPluginContextFactory
        return factory?.forModule(module) ?: factory?.forModule(module.id) ?: context
    }

    private companion object {
        /** Guest strings cross as UTF-8 whatever the JVM's default charset. */
        val JNA_OPTIONS: Map<String, Any> = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")

        const val ABI_VERSION_SYMBOL = "karandash_abi_version"

        /** Whether the library really exports [symbol] (JNA otherwise resolves lazily, at call time). */
        fun hasSymbol(
            library: NativeLibrary,
            symbol: String,
        ): Boolean =
            try {
                library.getFunction(symbol)
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
    }
}
