package io.karandash.loader.native

import com.sun.jna.NativeLibrary
import io.karandash.api.PluginContext
import io.karandash.loader.BridgeUtils
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.GuestExecutor
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.JnaCall
import io.karandash.loader.bridge.JnaTransport
import io.karandash.loader.bridge.TransportModuleBridge
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/**
 * Builds the [TransportModuleBridge] for a JNA-loaded native plugin over [JnaTransport]: the same bridge the
 * Kotlin/Native host builds over its C interop transport.
 */
internal object NativeModuleBridge {
    /**
     * [hasSymbol] tells whether the loaded library really exports a C symbol (looked up by the loader). Every
     * suspend guest call runs on the plugin's own lane and must finish within [timeout] plus the time the host spent
     * serving its host calls. A v2 guest in [symbols] gets the host-service channel, served on the context it was
     * initialized with ([loadContext] until then). Every guest symbol is reached through [call].
     */
    fun create(
        call: JnaCall,
        logger: Logger = NoOpLogger,
        timeout: Duration = GuestCallTimeout.DEFAULT,
        symbols: NativeLibrary? = null,
        loadContext: PluginContext? = null,
        hasSymbol: (String) -> Boolean = { true },
    ): TransportModuleBridge {
        val moduleId = JnaTransport(call, "", logger, hasSymbol).getProperty(BridgeConstants.PROP_ID).ifEmpty { "unknown" }
        val bridge = MutableStateFlow<TransportModuleBridge?>(null)
        val host = HostServiceDispatcher({ moduleId }, logger) { bridge.value?.initContext?.value ?: loadContext }
        val channel = symbols?.let { library -> JnaHostChannel(host).takeIf { it.register(library) } }
        val executor = GuestExecutor("JNA:$moduleId", timeout, logger) { host.hostTime }
        val transport = JnaTransport(call, moduleId, logger, hasSymbol, executor, channel)
        return TransportModuleBridge(
            transport = transport,
            id = moduleId,
            name = transport.getProperty("name"),
            version = transport.getProperty("version"),
            type = BridgeUtils.parseModuleType(transport.getProperty("type"), "native"),
            descriptor = BridgeDescriptor.parse(transport.getProperty(BridgeConstants.PROP_DESCRIBE), moduleId, logger),
        ).also { bridge.value = it }
    }
}
