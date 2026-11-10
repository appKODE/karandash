package io.karandash.subprocess

import kotlinx.serialization.Serializable

/**
 * JSON-based IPC protocol for the JAR plugin subprocess bridge.
 *
 * When the native binary discovers `.jar` plugins, it spawns a JVM subprocess
 * running a bridge server and communicates via stdin/stdout using these messages.
 *
 * Per-request flow (protocol v1, and the fallback for an older bridge JAR):
 * 1. Native sends a [JarBridgeRequest] as a single JSON line to the subprocess stdin.
 * 2. JVM subprocess processes the request and writes a [JarBridgeResponse] as a single JSON line to stdout.
 * 3. Native reads the response line and deserializes it.
 *
 * Session flow (protocol v2): the first line is a [JarBridgeProtocol.METHOD_HELLO] request; after the server's
 * answer both sides exchange [JarBridgeFrame] lines, so one JVM keeps every module instance alive and the module
 * calls the host's services ([JarBridgeProtocol.KIND_CALLBACK]) while a request runs.
 *
 * Every module type is served through [JarBridgeProtocol.METHOD_INVOKE]: the host's `TransportModuleBridge` sends
 * `BridgeAbi` ops with their string arguments, so the wire stays decoupled from model evolution.
 */
object JarBridgeProtocol {
    const val METHOD_DISCOVER = "discover"
    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_SHUTDOWN = "shutdown"

    /**
     * First line of a long-lived session (protocol v2): `params[PARAM_VERSION]` is the host's version. A v2 server
     * answers `success` with its own version in `data[PARAM_VERSION]` and then speaks [JarBridgeFrame] lines until
     * stdin closes; an older server answers "Unknown method" and exits, so the host falls back to one JVM per request.
     */
    const val METHOD_HELLO = "hello"

    /** Key in [JarBridgeRequest.params] and [JarBridgeResponse.data] of [METHOD_HELLO] carrying the protocol version. */
    const val PARAM_VERSION = "version"

    /** Version of the long-lived session protocol ([METHOD_HELLO], [JarBridgeFrame]). */
    const val PROTOCOL_VERSION = 2

    /** [JarBridgeFrame.kind]: host to server, [JarBridgeFrame.request] to run. */
    const val KIND_REQUEST = "request"

    /** [JarBridgeFrame.kind]: server to host, [JarBridgeFrame.response] to the request with the same id. */
    const val KIND_RESPONSE = "response"

    /**
     * [JarBridgeFrame.kind]: server to host, a host-service call (`HostServiceDispatcher` service and JSON payload)
     * made by module [JarBridgeFrame.moduleId].
     */
    const val KIND_CALLBACK = "callback"

    /** [JarBridgeFrame.kind]: host to server, the JSON reply ([JarBridgeFrame.payload]) to the callback with the same id. */
    const val KIND_CALLBACK_RESULT = "callbackResult"

    /**
     * Runs one `BridgeAbi` op ([PARAM_OP]) with its string arguments ([PARAM_ARGS], a JSON array) on the module and
     * answers the op's encoded result in [DATA_RESULT] (absent for a `null` result). Every type a module implements is
     * served through it; [BridgeModuleInfo.exports] lists the ops the module answers.
     */
    const val METHOD_INVOKE = "invoke"

    /**
     * Methods the `JarBridgeTransport` sends; the server must answer each one, which `JarBridgeServerMethodsTest`
     * checks.
     */
    val TRANSPORT_METHODS: Set<String> = setOf(METHOD_INITIALIZE, METHOD_SHUTDOWN, METHOD_INVOKE)

    /** Key in [JarBridgeRequest.params] carrying the `BridgeAbi` op name of a [METHOD_INVOKE] request. */
    const val PARAM_OP = "op"

    /** Key in [JarBridgeRequest.params] carrying the JSON array of string arguments of a [METHOD_INVOKE] request. */
    const val PARAM_ARGS = "args"

    /** Key in [JarBridgeResponse.data] carrying the encoded result of a [METHOD_INVOKE] request. */
    const val DATA_RESULT = "result"

    /**
     * Key in [JarBridgeRequest.params] carrying the host's serialized PluginContext (`BridgeContextSerializer`).
     * In per-request mode every request runs in a fresh JVM, so the server initializes the module with it before the
     * requested method; in a session it is read by `initialize` (and by a script's `execute`) only.
     */
    const val PARAM_CONTEXT = "context"
}

/**
 * One line of a long-lived session (protocol v2, after [JarBridgeProtocol.METHOD_HELLO]). Requests and callbacks are
 * numbered by their sender; a response or callback result carries the id of the frame it answers.
 */
@Serializable
data class JarBridgeFrame(
    val id: Long,
    val kind: String,
    val request: JarBridgeRequest? = null,
    val response: JarBridgeResponse? = null,
    val moduleId: String? = null,
    val service: String? = null,
    val payload: String? = null,
)

/**
 * Request sent from the native binary to the JVM bridge subprocess.
 */
@Serializable
data class JarBridgeRequest(
    val method: String,
    val moduleId: String? = null,
    val params: Map<String, String> = emptyMap(),
)

/**
 * Response from the JVM bridge subprocess to the native binary.
 */
@Serializable
data class JarBridgeResponse(
    val success: Boolean,
    val error: String? = null,
    val modules: List<BridgeModuleInfo>? = null,
    val data: Map<String, String>? = null,
)

/**
 * Module metadata returned by the discover method.
 */
@Serializable
data class BridgeModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val type: String,
    val description: String = "",
    val contributedRules: List<String> = emptyList(),
    val isDefaultEnabled: Boolean = false,
    val defaultEnabled: Boolean = isDefaultEnabled,
    /** [io.karandash.api.Plugin.minApiLevel]; absent from older bridge servers, so it defaults to the oldest level. */
    val minApiLevel: Int = io.karandash.api.KarandashApi.MIN_SUPPORTED_LEVEL,
    /** `ModuleDescriptor.properties` of the module; absent from older bridge servers, so SDK defaults apply. */
    val properties: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    /** `ModuleDescriptor.types` names: every type the module implements, primary first; absent from older servers. */
    val types: List<String> = emptyList(),
    /** `BridgeAbi` ops the module answers through [JarBridgeProtocol.METHOD_INVOKE]; absent from older servers. */
    val exports: List<String> = emptyList(),
    /**
     * Encoded value of every zero-argument `BridgeAbi` op (properties such as `formatId` or `providerId`) and priority
     * alias the module answers, read once at discovery before `initialize`.
     */
    val abi: Map<String, String> = emptyMap(),
)
