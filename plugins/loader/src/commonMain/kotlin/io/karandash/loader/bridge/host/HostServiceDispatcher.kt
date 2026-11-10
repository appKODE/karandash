package io.karandash.loader.bridge.host

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.support.rethrowCancellation
import io.karandash.loader.bridge.GuestContentPolicy
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.plusAssign
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The host side of `karandash_host_call(service, request_json) -> response_json`, one per guest instance and the
 * same for every transport (JS, WASM, JNA, C interop): same service names, same JSON, same limits. See
 * [HostServices] for the services and their request/response shapes. An unknown service answers
 * `{"err":"unsupported"}`; every failure answers `{"err": "..."}`, never throws into the guest.
 *
 * While a call to [withAnalyzer] or [withInterceptChain] runs, `ai.complete` and `ai.proceed` use that call's
 * [AiAnalyzerContext] or [AiInterceptChain] (its AI service and budget), never the context from `initialize`.
 *
 * A host call made while another host call of the same guest is still running answers `{"err":"reentrant"}`
 * (with a warn) instead of hanging the guest's runtime; [isReentrant] lets a transport refuse a guest call the
 * host makes while serving that guest's own host call.
 */
@OptIn(ExperimentalAtomicApi::class)
class HostServiceDispatcher(
    private val moduleId: () -> String,
    private val logger: Logger,
    private val context: () -> PluginContext?,
) {
    private val inCall = AtomicBoolean(false)
    private val hostNanos = AtomicLong(0L)
    private val running = AtomicReference<TimeMark?>(null)
    private val analyzer = AtomicReference<AiAnalyzerContext?>(null)
    private val interceptChain = AtomicReference<AiInterceptChain?>(null)
    private val perCallSlot = Mutex()
    private val marker = HostCallMarker(this)

    /**
     * Total time spent serving this guest's host calls, the one in progress included; guest-call deadlines are
     * extended by it.
     */
    val hostTime: Duration get() = hostNanos.load().nanoseconds + (running.load()?.elapsedNow() ?: Duration.ZERO)

    /**
     * Serves one host call from a non-suspend guest thread (QuickJS host function, WASM import, C callback),
     * never the main thread.
     */
    fun call(
        service: String,
        requestJson: String,
    ): String {
        if (!inCall.compareAndSet(expectedValue = false, newValue = true)) {
            logger.warn("[${moduleId()}] nested host call '$service' refused (reentrant)")
            return HostReply.err(HostReply.REENTRANT)
        }
        val started = TimeSource.Monotonic.markNow()
        running.store(started)
        try {
            // bridge-boundary: guest host_call arrives on the guest's own thread, which cannot suspend
            return runBlocking(Dispatchers.Default + marker) { dispatch(service, requestJson) }
        } finally {
            hostNanos += started.elapsedNow().inWholeNanoseconds
            running.store(null)
            inCall.store(false)
        }
    }

    /** As [call], for a caller that can suspend. */
    suspend fun dispatch(
        service: String,
        requestJson: String,
    ): String {
        val request =
            try {
                if (requestJson.isBlank()) JsonObject(emptyMap()) else KarandashJson.Default.parseToJsonElement(requestJson).jsonObject
            } catch (e: SerializationException) {
                return HostReply.err("bad request: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return HostReply.err("bad request: ${e.message}")
            }
        val ctx = context() ?: return HostReply.err("not initialized")
        return try {
            HostServices(moduleId(), ctx, analyzer.load(), interceptChain.load()).serve(service, request).toString()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: host service failure goes back to the guest as error JSON
            e.rethrowCancellation()
            logger.warn("[${moduleId()}] host service '$service' failed: ${e.message}")
            HostReply.err(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** True inside a host call this dispatcher serves: a guest call made from here would wait on the guest itself. */
    suspend fun isReentrant(): Boolean = currentCoroutineContext()[HostCallMarker]?.owner === this

    /** Runs [block] (one `analyze` call) with `ai.complete` bound to [context]. */
    suspend fun <T> withAnalyzer(
        context: AiAnalyzerContext,
        block: suspend () -> T,
    ): T = withSlot(analyzer, context, block)

    /** Runs [block] (one `intercept` call) with `ai.proceed` bound to [chain]. */
    suspend fun <T> withInterceptChain(
        chain: AiInterceptChain,
        block: suspend () -> T,
    ): T = withSlot(interceptChain, chain, block)

    private suspend fun <S, T> withSlot(
        slot: AtomicReference<S?>,
        value: S,
        block: suspend () -> T,
    ): T {
        if (isReentrant()) return block()
        // One per-call slot at a time: the guest runs one call at a time anyway, and a concurrent analyze
        // (ConsensusRun, one per model) must not see another call's context.
        return perCallSlot.withLock {
            slot.store(value)
            try {
                block()
            } finally {
                slot.store(null)
            }
        }
    }

    internal class HostCallMarker(
        val owner: HostServiceDispatcher,
    ) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> get() = Key

        companion object Key : CoroutineContext.Key<HostCallMarker>
    }
}

/** The services behind [HostServiceDispatcher], bound to one call's context. */
internal class HostServices(
    private val moduleId: String,
    private val context: PluginContext,
    private val analyzer: AiAnalyzerContext?,
    private val interceptChain: AiInterceptChain?,
) {
    suspend fun serve(
        service: String,
        request: JsonObject,
    ): JsonElement =
        when (service) {
            HostReply.LOG -> log(request)
            HostReply.CACHE_GET -> cacheGet(request)
            HostReply.CACHE_PUT -> cachePut(request)
            HostReply.RESOLVE_PATTERN -> resolvePattern(request)
            HostReply.RESOLVE_CONTENT -> resolveContent(request)
            HostReply.AI_COMPLETE -> HostAiServices.complete(context, analyzer, request)
            HostReply.AI_PROCEED -> HostAiServices.proceed(interceptChain, request)
            HostReply.SKIP -> skip(request)
            else -> HostReply.errJson(HostReply.UNSUPPORTED)
        }

    private fun log(request: JsonObject): JsonElement {
        val message = request.string("message").orEmpty()
        when (request.string("level")) {
            "warn", "error" -> context.warn(message)
            else -> context.log(message)
        }
        return HostReply.OK
    }

    /** `skip` (`{reason}`), inside `analyze` only: the rule shows as skipped with [reason], not "ran, no findings". */
    private fun skip(request: JsonObject): JsonElement {
        val call = analyzer ?: return HostReply.errJson("skip outside analyze")
        call.skip(request.required("reason"))
        return HostReply.OK
    }

    private suspend fun cacheGet(request: JsonObject): JsonElement {
        val cache = context.cacheProvider.getStringCache(request.required("cache"))
        val value = cache.get(prefixed(request.required("key")))
        return buildJsonObject { put("value", value?.let(::JsonPrimitive) ?: JsonNull) }
    }

    private suspend fun cachePut(request: JsonObject): JsonElement {
        val cache = context.cacheProvider.getStringCache(request.required("cache"))
        cache.put(prefixed(request.required("key")), request.required("value"))
        return HostReply.OK
    }

    private fun resolvePattern(request: JsonObject): JsonElement {
        val regex = context.resolvePattern(request.required("pattern"))
        return buildJsonObject {
            put("pattern", regex.pattern)
            putJsonArray("options") { regex.options.forEach { add(JsonPrimitive(it.name)) } }
        }
    }

    @OptIn(ExperimentalKarandashApi::class)
    private suspend fun resolveContent(request: JsonObject): JsonElement {
        val element = request["source"] ?: return HostReply.errJson("missing 'source'")
        val source = KarandashJson.Default.decodeFromJsonElement(ContentSource.serializer(), element)
        val policy = GuestContentPolicy(context.configDir) { context.resolveContent(it) }
        return buildJsonObject { put("content", policy.resolve(source)) }
    }

    private fun prefixed(key: String): String = "$moduleId:$key"
}

/** Wire constants and reply builders of the host-service channel. */
internal object HostReply {
    const val LOG = "log"
    const val CACHE_GET = "cache.get"
    const val CACHE_PUT = "cache.put"
    const val RESOLVE_PATTERN = "resolvePattern"
    const val RESOLVE_CONTENT = "resolveContent"
    const val AI_COMPLETE = "ai.complete"
    const val AI_PROCEED = "ai.proceed"
    const val SKIP = "skip"
    const val UNSUPPORTED = "unsupported"
    const val REENTRANT = "reentrant"

    val OK: JsonObject = JsonObject(emptyMap())

    fun errJson(message: String): JsonObject = buildJsonObject { put("err", message) }

    fun err(message: String): String = errJson(message).toString()
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.required(key: String): String = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "missing '$key'" }
