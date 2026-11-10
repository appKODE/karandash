package io.karandash.loader.jar

import io.karandash.api.support.rethrowCancellation
import io.karandash.loader.bridge.host.HostReply
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.joinWithin
import io.karandash.models.support.KarandashJson
import io.karandash.platform.InteractiveProcess
import io.karandash.platform.PlatformProcess
import io.karandash.sdk.Logger
import io.karandash.subprocess.JarBridgeFrame
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Host end of a long-lived JAR bridge session (protocol v2): one JVM ([command]) keeps every module instance alive
 * and serves [JarBridgeFrame] requests; its modules' host-service calls are answered by the [HostServiceDispatcher]
 * registered for them ([register]).
 *
 * A request that does not answer within [callTimeout] (extended by the time spent serving host calls meanwhile)
 * kills the JVM; the next request starts a new one, and the server initializes each module again from the context
 * every request carries ([JarBridgeProtocol.PARAM_CONTEXT]). [close] closes stdin (the server shuts its modules
 * down), waits [SHUTDOWN_GRACE] and kills what is left.
 *
 * @lifecycle per-run -- one per JAR loader run
 */
@OptIn(ExperimentalAtomicApi::class)
internal class JarBridgeSession private constructor(
    private val command: List<String>,
    private val callTimeout: Duration,
    private val startTimeout: Duration,
    private val logger: Logger,
) {
    private val json = KarandashJson.Wire
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(0L)
    private val openLock = Mutex()
    private val current = MutableStateFlow<Connection?>(null)
    private val hosts = MutableStateFlow<Map<String, HostServiceDispatcher>>(emptyMap())
    private val hostClock = MutableStateFlow(HostClock(Duration.ZERO, 0, null))

    /** Answers module [moduleId]'s host-service calls with [dispatcher]. */
    fun register(
        moduleId: String,
        dispatcher: HostServiceDispatcher,
    ) = hosts.update { it + (moduleId to dispatcher) }

    /** Sends [request] and waits for its response; a failure (dead JVM, timeout) reads as `success = false`. */
    suspend fun send(request: JarBridgeRequest): JarBridgeResponse {
        val connection = connection() ?: return failure("JAR bridge session could not be started")
        val id = requestIds.incrementAndFetch()
        val reply = CompletableDeferred<JarBridgeResponse>()
        connection.pending.update { it + (id to reply) }
        try {
            if (!connection.write(JarBridgeFrame(id, JarBridgeProtocol.KIND_REQUEST, request = request))) {
                drop(connection, "JAR bridge session is gone")
            }
            if (reply.joinWithin(callTimeout) { hostTime() }) return reply.await()
            logger.warn("JAR bridge '${request.method}' for ${request.moduleId} timed out after $callTimeout; restarting the JVM")
            drop(connection, "JAR bridge restarted after a timeout")
            return failure("JAR bridge '${request.method}' timed out after $callTimeout")
        } finally {
            connection.pending.update { it - id }
        }
    }

    /** Closes stdin, gives the JVM [SHUTDOWN_GRACE] to shut its modules down, then kills it. */
    suspend fun close() {
        current.getAndUpdate { null }?.let { connection ->
            connection.process.closeStdin()
            if (withTimeoutOrNull(SHUTDOWN_GRACE) { connection.reader.join() } == null) {
                logger.debug("JAR bridge did not exit within $SHUTDOWN_GRACE; killing it")
            }
            connection.process.kill()
            connection.failAll("JAR bridge session closed")
        }
        scope.cancel()
    }

    private suspend fun connection(): Connection? =
        openLock.withLock {
            current.value ?: open()?.also { current.value = it }
        }

    /** Kills [connection]'s JVM and fails its requests; the next request starts a new one. */
    private fun drop(
        connection: Connection,
        reason: String,
    ) {
        current.compareAndSet(connection, null)
        connection.process.kill()
        connection.failAll(reason)
    }

    /** Starts a JVM and says `hello`; `null` when it cannot start or does not speak protocol v2 (an older bridge JAR). */
    private suspend fun open(): Connection? {
        val process = try {
            PlatformProcess.spawnInteractive(command)
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            logger.warn("Cannot start the JAR bridge: ${e.message}")
            return null
        }
        val hello = JarBridgeRequest(
            JarBridgeProtocol.METHOD_HELLO,
            params = mapOf(JarBridgeProtocol.PARAM_VERSION to JarBridgeProtocol.PROTOCOL_VERSION.toString()),
        )
        val answer = CompletableDeferred<String?>()
        val handshake = scope.launch {
            answer.complete(if (process.writeLine(json.encodeToString(hello))) process.readLine() else null)
        }
        val line = withTimeoutOrNull(startTimeout) { answer.await() }
        val version = line
            ?.let(::decodeResponse)
            ?.takeIf { it.success }
            ?.data
            ?.get(JarBridgeProtocol.PARAM_VERSION)
            ?.toIntOrNull()
        if (version == null || version < JarBridgeProtocol.PROTOCOL_VERSION) {
            process.kill()
            handshake.cancel()
            logger.debug("JAR bridge does not speak session protocol v${JarBridgeProtocol.PROTOCOL_VERSION}: ${line ?: "no answer"}")
            return null
        }
        scope.launch { forwardStderr(process) }
        return Connection(process)
    }

    private suspend fun read(connection: Connection) {
        while (true) {
            val line = connection.process.readLine() ?: break
            val frame = try {
                json.decodeFromString<JarBridgeFrame>(line)
            } catch (e: SerializationException) {
                logger.warn("Bad JAR bridge frame: ${e.message}")
                continue
            }
            when (frame.kind) {
                JarBridgeProtocol.KIND_RESPONSE -> {
                    val response = frame.response ?: failure("Response frame without a response")
                    connection.pending.value[frame.id]?.complete(response)
                }

                JarBridgeProtocol.KIND_CALLBACK -> {
                    scope.launch {
                        connection.write(
                            JarBridgeFrame(frame.id, JarBridgeProtocol.KIND_CALLBACK_RESULT, payload = serve(frame))
                        )
                    }
                }

                else -> {
                    logger.warn("Unknown JAR bridge frame kind: ${frame.kind}")
                }
            }
        }
        current.compareAndSet(connection, null)
        connection.failAll("JAR bridge process exited")
    }

    /** One host-service call of a module; the time it takes does not count against that request's timeout. */
    private suspend fun serve(frame: JarBridgeFrame): String {
        val host = frame.moduleId?.let { hosts.value[it] } ?: return HostReply.err("no host for module ${frame.moduleId}")
        hostClock.update { it.begin() }
        try {
            return host.dispatch(frame.service.orEmpty(), frame.payload.orEmpty())
        } finally {
            hostClock.update { it.end() }
        }
    }

    // limit: one clock for the session, so any module's host call extends every running request; per-module if it matters
    private fun hostTime(): Duration = hostClock.value.let { it.spent + (it.since?.elapsedNow() ?: Duration.ZERO) }

    private suspend fun forwardStderr(process: InteractiveProcess) {
        while (true) {
            val line = process.readErrorLine() ?: break
            if (line.isNotBlank()) logger.debug(line)
        }
    }

    private fun decodeResponse(line: String): JarBridgeResponse? =
        try {
            json.decodeFromString<JarBridgeResponse>(line)
        } catch (e: SerializationException) {
            logger.debug("Unreadable JAR bridge hello answer: ${e.message}")
            null
        }

    /** One JVM: its pending requests by id and the coroutine reading its stdout. */
    private inner class Connection(
        val process: InteractiveProcess,
    ) {
        val pending = MutableStateFlow<Map<Long, CompletableDeferred<JarBridgeResponse>>>(emptyMap())
        private val writeLock = Mutex()
        val reader: Job = scope.launch { read(this@Connection) }

        suspend fun write(frame: JarBridgeFrame): Boolean {
            val line = json.encodeToString(frame)
            return writeLock.withLock { withContext(Dispatchers.IO) { process.writeLine(line) } }
        }

        fun failAll(reason: String) = pending.getAndUpdate { emptyMap() }.values.forEach { it.complete(failure(reason)) }
    }

    /** Time spent serving host calls: [spent] in finished ones, plus the time since [since] while [active] run. */
    private class HostClock(
        val spent: Duration,
        val active: Int,
        val since: TimeMark?,
    ) {
        fun begin() = if (active == 0) HostClock(spent, 1, TimeSource.Monotonic.markNow()) else HostClock(spent, active + 1, since)

        fun end() =
            if (active == 1) {
                HostClock(spent + (since?.elapsedNow() ?: Duration.ZERO), 0, null)
            } else {
                HostClock(spent, active - 1, since)
            }
    }

    companion object {
        val SHUTDOWN_GRACE: Duration = 5.seconds

        private fun failure(reason: String) = JarBridgeResponse(success = false, error = reason)

        /**
         * Starts a session running [command] (`java ... JarBridgeServer`); `null` when the JVM cannot start or the bridge
         * JAR predates the session protocol, so the caller falls back to one JVM per request.
         */
        suspend fun open(
            command: List<String>,
            callTimeout: Duration,
            startTimeout: Duration,
            logger: Logger,
        ): JarBridgeSession? {
            val session = JarBridgeSession(command, callTimeout, startTimeout, logger)
            if (session.connection() != null) return session
            session.scope.cancel()
            return null
        }
    }
}
