package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.module.RuleModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.support.KarandashJson
import io.karandash.subprocess.BridgeModuleInfo
import io.karandash.subprocess.JarBridgeFrame
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.OutputStream
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * JVM-side bridge server for JAR plugin loading via subprocess IPC.
 *
 * When the native binary encounters `.jar` plugins, it spawns a JVM process running this server. The first stdin
 * line decides the mode:
 * - [JarBridgeProtocol.METHOD_HELLO] (protocol v2): a long-lived session ([JarBridgeServerLoop]) that keeps every
 *   module instance alive and lets modules call the host's services while a request runs;
 * - any other [JarBridgeRequest]: single-shot, one [JarBridgeResponse] line and exit (protocol v1).
 *
 * stdout is the wire: the server keeps it for protocol lines and points `System.out` at stderr, so a plugin's
 * `println` cannot corrupt the protocol.
 *
 * Usage: `java -cp <bridge-jar>:<plugin-jars> io.karandash.loader.jar.JarBridgeServer`
 */
object JarBridgeServer {
    private val json = KarandashJson.Wire

    internal val moduleCache: MutableMap<String, Module> = ConcurrentHashMap()

    private fun moduleNotFoundResponse(moduleId: String) = JarBridgeResponse(success = false, error = "Module not found: $moduleId")

    /** Entry point for the bridge subprocess: a session after `hello`, otherwise one request and its response. */
    @JvmStatic
    fun main(args: Array<String>) {
        val wire = System.out
        System.setOut(System.err)
        try {
            serve(System.`in`.bufferedReader(), wire)
        } finally {
            System.setOut(wire)
        }
    }

    private fun serve(
        input: BufferedReader,
        wire: OutputStream,
    ) {
        val line = input.readLine()
            ?: return writeLine(wire, json.encodeToString(JarBridgeResponse(success = false, error = "No input")))

        val request = try {
            json.decodeFromString<JarBridgeRequest>(line)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: JAR bridge subprocess returns every failure as an error response
            return writeLine(wire, json.encodeToString(JarBridgeResponse(success = false, error = "Invalid request: ${e.message}")))
        }

        if (request.method == JarBridgeProtocol.METHOD_HELLO) {
            val version = mapOf(JarBridgeProtocol.PARAM_VERSION to JarBridgeProtocol.PROTOCOL_VERSION.toString())
            writeLine(wire, json.encodeToString(JarBridgeResponse(success = true, data = version)))
            // bridge-boundary: JAR bridge subprocess main thread runs the session until stdin closes
            runBlocking { JarBridgeServerLoop(input, wire).serve() }
            return
        }
        writeLine(wire, json.encodeToString(handleRequest(request)))
    }

    /** One request outside a session: every request runs in a fresh JVM, so the module is initialized first. */
    internal fun handleRequest(request: JarBridgeRequest): JarBridgeResponse =
        // bridge-boundary: JAR bridge subprocess serves one blocking stdin/stdout request at a time
        runBlocking { handle(request, null) }

    /** Serves [request]; [session] is the long-lived session it arrived on, `null` for a single-shot request. */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun handle(
        request: JarBridgeRequest,
        session: JarBridgeServerLoop?,
    ): JarBridgeResponse =
        try {
            when (request.method) {
                JarBridgeProtocol.METHOD_DISCOVER -> handleDiscover()
                JarBridgeProtocol.METHOD_INITIALIZE -> handleInitialize(request, session)
                JarBridgeProtocol.METHOD_INVOKE -> handleInvoke(request, session)
                JarBridgeProtocol.METHOD_SHUTDOWN -> handleShutdown(request, session)
                else -> JarBridgeResponse(success = false, error = "Unknown method: ${request.method}")
            }
        } catch (e: Exception) {
            // boundary: JAR bridge subprocess returns every failure as an error response
            e.rethrowCancellation()
            JarBridgeResponse(success = false, error = "Internal error: ${e.message}")
        }

    @Suppress("TooGenericExceptionCaught")
    private fun handleDiscover(): JarBridgeResponse {
        val modules = try {
            ServiceLoader.load(Module::class.java).toList()
        } catch (e: Exception) {
            // boundary: JAR bridge subprocess returns every failure as an error response
            return JarBridgeResponse(success = false, error = "ServiceLoader failed: ${e.message}")
        }

        // Cache modules for subsequent calls
        modules.forEach { moduleCache[it.id] = it }

        val infos = modules.map(::describe)

        return JarBridgeResponse(success = true, modules = infos)
    }

    /** Discovery entry for [module]: its metadata, descriptor, the ops it answers and the property snapshot. */
    @OptIn(io.karandash.api.ExperimentalKarandashApi::class)
    internal fun describe(module: Module): BridgeModuleInfo {
        // bridge-boundary: non-suspend server entry point calling suspend SDK methods
        val contributedRules = if (module is RuleModule) runBlocking { module.contributedRules() }.toList() else emptyList()
        val descriptor = ModuleDescriptor.of(module)
        // bridge-boundary: JAR bridge subprocess serves one blocking stdin/stdout request at a time
        val abi = runBlocking { JarBridgeDispatcher.snapshot(module) { JarBridgePluginContext.fromJson(it) } }
        return BridgeModuleInfo(
            id = module.id,
            name = module.name,
            version = module.version,
            type = module.type.name,
            description = module.description,
            contributedRules = contributedRules,
            isDefaultEnabled = module.defaultEnabled,
            defaultEnabled = module.defaultEnabled,
            minApiLevel = module.minApiLevel,
            properties = descriptor.properties,
            types = descriptor.types,
            exports = JarBridgeDispatcher.exports(module),
            abi = abi,
        )
    }

    private suspend fun handleInitialize(
        request: JarBridgeRequest,
        session: JarBridgeServerLoop?,
    ): JarBridgeResponse {
        val moduleId = request.moduleId
            ?: return JarBridgeResponse(success = false, error = "moduleId required for initialize")

        val module = resolveModule(moduleId)
            ?: return moduleNotFoundResponse(moduleId)

        initializeFailure(module, request, session)?.let { return it }
        // the properties as they read after initialize: the host answers from these instead of the discovery snapshot
        val channel = session?.channel(moduleId)
        val live = JarBridgeDispatcher.snapshot(module) { JarBridgePluginContext.fromJson(it, channel) }
        return JarBridgeResponse(success = true, data = live)
    }

    /**
     * Initializes [module] with the host's context ([JarBridgeProtocol.PARAM_CONTEXT]); `null` when that succeeded.
     * In a session the context is kept for the module's later calls.
     */
    private suspend fun initializeFailure(
        module: Module,
        request: JarBridgeRequest,
        session: JarBridgeServerLoop?,
    ): JarBridgeResponse? {
        val context = JarBridgePluginContext.fromJson(request.params[JarBridgeProtocol.PARAM_CONTEXT], session?.channel(module.id))
        val initialized = module.initialize(context)
        if (initialized) session?.contexts?.put(module.id, context)
        return if (initialized) null else JarBridgeResponse(success = false, error = "Module.initialize() returned false")
    }

    /**
     * Runs one `BridgeAbi` op on the module; the op's encoded result rides in [JarBridgeProtocol.DATA_RESULT], absent
     * for a `null` result. Outside a session every request runs in a fresh JVM, so the module is initialized first
     * ([initializeFailure]); in a session it keeps the instance `initialize` set up.
     */
    private suspend fun handleInvoke(
        request: JarBridgeRequest,
        session: JarBridgeServerLoop?,
    ): JarBridgeResponse {
        val moduleId = request.moduleId
            ?: return JarBridgeResponse(success = false, error = "moduleId required for invoke")
        val module = resolveModule(moduleId)
            ?: return moduleNotFoundResponse(moduleId)
        val op = request.params[JarBridgeProtocol.PARAM_OP]
            ?: return JarBridgeResponse(success = false, error = "Missing op param")
        val args = json.decodeFromString(ListSerializer(String.serializer()), request.params[JarBridgeProtocol.PARAM_ARGS] ?: "[]")
        val initialized = session?.contexts?.get(moduleId)
        if (initialized == null) initializeFailure(module, request, session)?.let { return it }
        val channel = session?.channel(moduleId)
        val call = JarInvocation(args, channel) { context ->
            context?.let { JarBridgePluginContext.fromJson(it, channel) }
                ?: initialized
                ?: JarBridgePluginContext.fromJson(request.params[JarBridgeProtocol.PARAM_CONTEXT], channel)
        }
        val result = JarBridgeDispatcher.invoke(module, op, call)
        return JarBridgeResponse(success = true, data = result?.let { mapOf(JarBridgeProtocol.DATA_RESULT to it) })
    }

    private suspend fun handleShutdown(
        request: JarBridgeRequest,
        session: JarBridgeServerLoop?,
    ): JarBridgeResponse {
        val moduleId = request.moduleId
        if (moduleId != null) {
            moduleCache.remove(moduleId)?.shutdown()
            session?.contexts?.remove(moduleId)
        } else {
            moduleCache.values.forEach { m -> m.shutdown() }
            moduleCache.clear()
            session?.contexts?.clear()
        }
        return JarBridgeResponse(success = true)
    }

    private fun resolveModule(moduleId: String): Module? {
        moduleCache[moduleId]?.let { return it }
        // Lazy discovery if not cached
        val modules = ServiceLoader.load(Module::class.java).toList()
        modules.forEach { moduleCache[it.id] = it }
        return moduleCache[moduleId]
    }

    /**
     * Writes one protocol line to [wire], the process's real stdout. Raw output is deliberate: stdout is the JSON wire
     * protocol the native host parses, not a log channel, so it must carry protocol lines and nothing else (plugin
     * logs go to stderr or the host via [JarBridgePluginContext]). This file is listed in the raw-output
     * `RawConsoleOutput` excludes in `detekt.yml` for that reason.
     */
    internal fun writeLine(
        wire: OutputStream,
        line: String,
    ) {
        synchronized(wire) {
            wire.write((line + "\n").encodeToByteArray())
            wire.flush()
        }
    }
}

/**
 * The server end of a long-lived session (protocol v2): reads [JarBridgeFrame] lines from [input], runs each request
 * concurrently and answers on [wire], and carries the modules' host-service calls ([channel]) to the host. When the
 * host closes stdin the running requests are cancelled and every module is shut down.
 *
 * @lifecycle per-run -- one per session JVM
 */
@OptIn(ExperimentalAtomicApi::class)
internal class JarBridgeServerLoop(
    private val input: BufferedReader,
    private val wire: OutputStream,
) {
    private val json = KarandashJson.Wire
    private val callbackIds = AtomicLong(0L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()

    /** Context each module was initialized with in this session. */
    val contexts: MutableMap<String, JarBridgePluginContext> = ConcurrentHashMap()

    suspend fun serve() {
        coroutineScope {
            while (true) {
                val line = withContext(Dispatchers.IO) { input.readLine() } ?: break
                val frame = try {
                    json.decodeFromString<JarBridgeFrame>(line)
                } catch (e: SerializationException) {
                    System.err.println("[jar-bridge:warn] Bad session frame: ${e.message}")
                    continue
                }
                when (frame.kind) {
                    JarBridgeProtocol.KIND_REQUEST -> launch(Dispatchers.Default) { answer(frame) }
                    JarBridgeProtocol.KIND_CALLBACK_RESULT -> pending.remove(frame.id)?.complete(frame.payload.orEmpty())
                    else -> System.err.println("[jar-bridge:warn] Unknown session frame kind: ${frame.kind}")
                }
            }
            coroutineContext.cancelChildren()
        }
        pending.values.forEach { it.complete(SESSION_CLOSED) }
        JarBridgeServer.handle(JarBridgeRequest(JarBridgeProtocol.METHOD_SHUTDOWN), this)
    }

    private suspend fun answer(frame: JarBridgeFrame) {
        val response = frame.request?.let { JarBridgeServer.handle(it, this) }
            ?: JarBridgeResponse(success = false, error = "Request frame without a request")
        write(JarBridgeFrame(frame.id, JarBridgeProtocol.KIND_RESPONSE, response = response))
    }

    private fun write(frame: JarBridgeFrame) = JarBridgeServer.writeLine(wire, json.encodeToString(frame))

    /** Module [moduleId]'s host-service channel. */
    fun channel(moduleId: String): JarHostCall =
        object : JarHostCall {
            override suspend fun call(
                service: String,
                request: JsonObject,
            ): JsonObject {
                val id = callbackIds.incrementAndFetch()
                val reply = CompletableDeferred<String>()
                pending[id] = reply
                try {
                    write(callback(id, service, request))
                    return parse(reply.await())
                } finally {
                    pending.remove(id)
                }
            }

            override fun callBlocking(
                service: String,
                request: JsonObject,
            ): JsonObject =
                // bridge-boundary: PluginContext.resolvePattern cannot suspend; the reply arrives on the reader coroutine
                runBlocking { call(service, request) }

            override fun notify(
                service: String,
                request: JsonObject,
            ) = write(callback(callbackIds.incrementAndFetch(), service, request))

            private fun callback(
                id: Long,
                service: String,
                request: JsonObject,
            ) = JarBridgeFrame(id, JarBridgeProtocol.KIND_CALLBACK, moduleId = moduleId, service = service, payload = request.toString())
        }

    private fun parse(reply: String): JsonObject =
        try {
            json.parseToJsonElement(reply) as? JsonObject ?: errJson("reply is not an object")
        } catch (e: SerializationException) {
            errJson("bad reply: ${e.message}")
        }

    private companion object {
        val SESSION_CLOSED = errJson("session closed").toString()

        fun errJson(message: String) = buildJsonObject { put("err", message) }
    }
}
