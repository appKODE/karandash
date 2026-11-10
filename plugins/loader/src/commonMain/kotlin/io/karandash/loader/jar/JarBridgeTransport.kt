package io.karandash.loader.jar

import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeAbi
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.subprocess.BridgeModuleInfo
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * [BridgeTransport] over the JAR bridge subprocess: every `BridgeAbi` op the server lists in [BridgeModuleInfo.exports]
 * runs through [JarBridgeProtocol.METHOD_INVOKE], and zero-argument ops (properties) are answered from the snapshot
 * the server returns with a successful `initialize` (else the [BridgeModuleInfo.abi] one taken at discovery), so a JAR module is driven by the same `TransportModuleBridge` as
 * a native or JS guest.
 *
 * The context passed to `initialize` is kept and sent with every later request ([JarBridgeProtocol.PARAM_CONTEXT]):
 * in per-request mode each request runs in a fresh JVM, which initializes the module with it before the call; in a
 * session ([JarBridgeSession]) a JVM restarted after a timeout initializes the module with it again.
 *
 * @param send Sends one request to the bridge server (a [JarBridgeSession], the native `JarBridgeClient.send`, or a
 *   loopback in tests).
 * @param host The module's host-service channel in a session; `null` in per-request mode.
 */
internal class JarBridgeTransport(
    private val send: suspend (JarBridgeRequest) -> JarBridgeResponse,
    private val info: BridgeModuleInfo,
    private val logger: Logger = NoOpLogger,
    override val host: HostServiceDispatcher? = null,
) : BridgeTransport {
    private val initContext = MutableStateFlow<String?>(null)

    // the property snapshot the server read after the last successful initialize; empty before it and after shutdown
    private val liveProps = MutableStateFlow<Map<String, String>>(emptyMap())
    private val argsSerializer = ListSerializer(String.serializer())

    override suspend fun call(
        method: String,
        vararg args: String,
    ): String? {
        when (method) {
            JarBridgeProtocol.METHOD_INITIALIZE -> {
                args.firstOrNull()?.let { initContext.value = it }
                // initialize answers false through success=false, which must not read as "no answer" (treated as true)
                val response = request(JarBridgeProtocol.METHOD_INITIALIZE)
                liveProps.value = response.data.takeIf { response.success }.orEmpty()
                return response.success.toString()
            }

            JarBridgeProtocol.METHOD_SHUTDOWN -> {
                liveProps.value = emptyMap()
                return answer(method, request(JarBridgeProtocol.METHOD_SHUTDOWN))
            }
        }
        if (args.isEmpty()) snapshot(method)?.let { return it }
        // (scripts, mr, context): the script's own context replaces the one kept from initialize
        val callContext = args.getOrNull(2)?.takeIf { method == OP_EXECUTE }?.let { mapOf(JarBridgeProtocol.PARAM_CONTEXT to it) }
        val invoke = mapOf(
            JarBridgeProtocol.PARAM_OP to method,
            JarBridgeProtocol.PARAM_ARGS to KarandashJson.Wire.encodeToString(argsSerializer, args.toList()),
        ) + callContext.orEmpty()
        return answer(method, request(JarBridgeProtocol.METHOD_INVOKE, invoke))
    }

    override suspend fun callVoid(
        method: String,
        vararg args: String,
    ) {
        suspendCatching { call(method, *args) }
            .onFailure { logger.warn("JAR bridge call '$method' failed for ${info.id}: ${it.message}") }
    }

    override fun getProperty(name: String): String =
        when (name) {
            BridgeConstants.PROP_ID -> info.id
            BridgeConstants.PROP_NAME -> info.name
            BridgeConstants.PROP_VERSION -> info.version
            PROP_TYPE -> info.type
            PROP_DESCRIPTION -> info.description
            else -> snapshot(name).orEmpty()
        }

    override fun getIntProperty(
        name: String,
        default: Int,
    ): Int = snapshot(name)?.toIntOrNull() ?: default

    override fun getBoolProperty(
        name: String,
        default: Boolean,
    ): Boolean = snapshot(name)?.toBooleanStrictOrNull() ?: default

    override fun hasCapability(name: String): Boolean =
        name == JarBridgeProtocol.METHOD_INITIALIZE ||
            name == JarBridgeProtocol.METHOD_SHUTDOWN ||
            (BridgeAbi.find(name)?.op ?: name) in info.exports

    override fun logError(
        method: String,
        exception: Exception,
    ) {
        logger.warn("JAR bridge error in '$method' for ${info.id}: ${exception.message}")
    }

    override fun logInfo(
        method: String,
        message: String,
    ) {
        logger.info("[JAR:${info.id}] $message")
    }

    /**
     * A property as read after initialize, else at discovery: the name itself (priority aliases are keyed by name),
     * else its op.
     */
    private fun snapshot(name: String): String? = lookup(liveProps.value, name) ?: lookup(info.abi, name)

    private fun lookup(
        props: Map<String, String>,
        name: String,
    ): String? = props[name] ?: BridgeAbi.find(name)?.op?.let { props[it] }

    private suspend fun request(
        method: String,
        params: Map<String, String> = emptyMap(),
    ): JarBridgeResponse {
        val context = initContext.value?.let { mapOf(JarBridgeProtocol.PARAM_CONTEXT to it) }.orEmpty()
        return send(JarBridgeRequest(method = method, moduleId = info.id, params = context + params))
    }

    /** The result of a successful request; a failed one is logged and reads as "no answer". */
    private fun answer(
        method: String,
        response: JarBridgeResponse,
    ): String? {
        if (!response.success) {
            logger.warn("JAR bridge call '$method' failed for ${info.id}: ${response.error}")
            return null
        }
        return response.data?.get(JarBridgeProtocol.DATA_RESULT)
    }

    private companion object {
        const val PROP_TYPE = "type"
        const val PROP_DESCRIPTION = "description"
        const val OP_EXECUTE = "execute"
    }
}
