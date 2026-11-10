@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.loader.native

import io.karandash.api.PluginContext
import io.karandash.loader.BridgeUtils
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.GuestExecutor
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.CInteropTransport
import io.karandash.loader.bridge.TransportModuleBridge
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/** Builds the [TransportModuleBridge] for a dlopen'ed native plugin over [CInteropTransport]. */
internal object NativeModuleBridge {
    /**
     * Reads the module id before building the transport; the bridge closes [handle] on unload. Every suspend
     * guest call runs on the plugin's own lane and must finish within [timeout] plus the time the host spent serving
     * its host calls. A v2 guest gets the host-service channel, served on the context it was initialized with
     * ([loadContext] until then).
     */
    fun create(
        handle: COpaquePointer,
        logger: Logger = NoOpLogger,
        timeout: Duration = GuestCallTimeout.DEFAULT,
        loadContext: PluginContext? = null,
    ): TransportModuleBridge {
        val moduleId = CInteropTransport(handle, "", logger).readNativeModuleId().ifEmpty { "unknown" }
        val bridge = MutableStateFlow<TransportModuleBridge?>(null)
        val host = HostServiceDispatcher({ moduleId }, logger) { bridge.value?.initContext?.value ?: loadContext }
        val channel = CInteropHostChannel(host)
        val connected = channel.register(handle)
        if (!connected) channel.dispose()
        val executor = GuestExecutor("Native:$moduleId", timeout, logger) { host.hostTime }
        val transport = CInteropTransport(handle, moduleId, logger, executor, host.takeIf { connected })
        return TransportModuleBridge(
            transport = transport,
            id = moduleId,
            name = transport.getProperty("name"),
            version = transport.getProperty("version"),
            type = BridgeUtils.parseModuleType(transport.getProperty("type"), "native"),
            descriptor = BridgeDescriptor.parse(transport.getProperty(BridgeConstants.PROP_DESCRIBE), moduleId, logger),
            onClose = {
                transport.closeHandle()
                if (connected) channel.dispose()
            },
        ).also { bridge.value = it }
    }
}
