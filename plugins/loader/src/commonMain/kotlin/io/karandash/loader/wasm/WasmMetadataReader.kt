package io.karandash.loader.wasm

import io.karandash.api.support.rethrowCancellation
import io.karandash.loader.GuestCallTimeout
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.readBytes
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.wasm.createWasmEngine
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * Metadata extracted from a WASM plugin file without full initialization.
 */
@Serializable
data class WasmPluginMetadata(
    val id: String,
    val name: String,
    val version: String,
)

/**
 * Reads plugin metadata from a `.wasm` file by loading it into a temporary
 * WASM engine and querying the ABI exports.
 *
 * Does not initialize or register the plugin - only reads identity fields.
 */
object WasmMetadataReader {
    /**
     * Read metadata from a WASM plugin file.
     *
     * Each guest call is bounded by [GuestCallTimeout.fromEnvironment], like a full load.
     *
     * @return metadata or `null` if the file is not a valid karandash WASM plugin
     */
    @Suppress("TooGenericExceptionCaught") // WASM runtime boundary
    fun readMetadata(
        path: Path,
        logger: Logger = NoOpLogger,
        env: (String) -> String? = PlatformEnvironment::getEnv,
    ): WasmPluginMetadata? {
        return try {
            val wasmBytes = path.readBytes()
            val engine =
                createWasmEngine(
                    wasmBytes,
                    GuestCallTimeout.fromEnvironment(logger, env),
                    onTimeout = WasmTimeoutWatchdog(logger)::onTimeout
                )
            try {
                val abiVersion = engine.callI32(WasmAbi.ABI_VERSION)
                if (!io.karandash.loader.NativeAbi
                        .isSupported(abiVersion)
                ) {
                    return null
                }

                val bridge = WasmModuleBridge(engine)
                WasmPluginMetadata(
                    id = bridge.id,
                    name = bridge.name,
                    version = bridge.version,
                )
            } catch (e: Exception) {
                // boundary: runs a guest WASM module
                e.rethrowCancellation()
                null
            } finally {
                engine.close()
            }
        } catch (e: Exception) {
            // boundary: runs a guest WASM module
            e.rethrowCancellation()
            null
        }
    }
}
