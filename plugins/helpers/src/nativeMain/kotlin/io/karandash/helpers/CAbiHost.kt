@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.cache.CacheProvider
import io.karandash.api.cache.KarandashCache
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** `char* (*)(void* host_ctx, const char* service, const char* request_json)`: the host's `karandash_host_call`. */
typealias CAbiHostCallFn = CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> CPointer<ByteVar>?>>

/** `void (*)(char* ptr)`: releases a response the host returned. */
typealias CAbiHostFreeFn = CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>

/**
 * The guest end of the ABI v2 host-service channel, set by the generated `karandash_set_host` export before
 * `initialize`. Without it (a v1 host) every call answers `{"err":"unsupported"}` and the context keeps its local
 * fallbacks.
 */
object CAbiHost {
    /** The host-channel ABI this guest speaks. */
    const val ABI: Int = 2

    private class Link(
        val ctx: COpaquePointer?,
        val call: CAbiHostCallFn,
        val free: CAbiHostFreeFn,
    )

    private val link = atomic<Link?>(null)

    val connected: Boolean get() = link.value != null

    /** Answers 1 when the host's [abi] is spoken here and the channel is set, else 0 (the host then treats it as v1). */
    fun set(
        abi: Int,
        ctx: COpaquePointer?,
        call: CAbiHostCallFn?,
        free: CAbiHostFreeFn?,
    ): Int {
        if (abi != ABI || call == null || free == null) return 0
        link.value = Link(ctx, call, free)
        return 1
    }

    /** One host call: the response JSON, or `{"err": ...}`. */
    fun call(
        service: String,
        request: String,
    ): String {
        val current = link.value ?: return """{"err":"unsupported"}"""
        val response =
            memScoped { current.call(current.ctx, service.cstr.ptr, request.cstr.ptr) }
                ?: return """{"err":"no response"}"""
        return try {
            response.toKString()
        } finally {
            current.free(response)
        }
    }

    /** As [call] with a JSON request, parsed; a reply that is not a JSON object reads as `{"err": ...}`. */
    internal fun request(
        service: String,
        request: JsonObject,
    ): JsonObject {
        val reply = call(service, request.toString())
        return try {
            KarandashJson.Default.parseToJsonElement(reply).jsonObject
        } catch (e: SerializationException) {
            buildJsonObject { put("err", "bad host reply: ${e.message}") }
        } catch (e: IllegalArgumentException) {
            buildJsonObject { put("err", "bad host reply: ${e.message}") }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Info, warn and error go to the host log; debug stays quiet, as before the channel. */
internal class HostLogger(
    private val fallback: Logger,
) : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = send("info", message) { fallback.info(it) }

    override fun warn(message: String) = send("warn", message) { fallback.warn(it) }

    override fun error(message: String) = send("error", message) { fallback.error(it) }

    private fun send(
        level: String,
        message: String,
        orElse: (String) -> Unit,
    ) {
        val reply =
            CAbiHost.request(
                "log",
                buildJsonObject {
                    put("level", level)
                    put("message", message)
                },
            )
        if (reply.str("err") != null) orElse(message)
    }
}

/** The host's cache (keys prefixed with the module id by the host). */
internal object HostCacheProvider : CacheProvider {
    override val enabled: Boolean = true

    override fun getStringCache(
        name: String,
        maxEntries: Int,
    ): KarandashCache = HostCache(name)
}

// limit: invalidate/clear/size are not host services; add them to the channel when a guest needs them
internal class HostCache(
    private val name: String,
) : KarandashCache {
    override suspend fun get(key: String): String? =
        CAbiHost
            .request(
                "cache.get",
                buildJsonObject {
                    put("cache", name)
                    put("key", key)
                },
            ).str("value")

    override suspend fun put(
        key: String,
        value: String,
        ttlMs: Long,
    ) {
        CAbiHost.request(
            "cache.put",
            buildJsonObject {
                put("cache", name)
                put("key", key)
                put("value", value)
            },
        )
    }

    override suspend fun getOrPut(
        key: String,
        compute: suspend () -> String,
    ): String = get(key) ?: compute().also { put(key, it) }

    override suspend fun getOrPut(
        key: String,
        dependencies: Map<String, String>,
        compute: suspend () -> String,
    ): String = getOrPut(dependencies.entries.sortedBy { it.key }.joinToString("|", "$key|") { "${it.key}=${it.value}" }, compute)

    override suspend fun invalidate(key: String) = Unit

    override suspend fun clear() = Unit

    override fun size(): Int = 0
}

/** `ai.complete` through the host: its provider, interceptors and (inside `analyze`) its budget and context blocks. */
internal class HostAiService(
    override val isAvailable: Boolean = true,
) : AiService {
    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        val flat = request.withContextInPrompt()
        val reply =
            CAbiHost.request(
                "ai.complete",
                buildJsonObject {
                    put("prompt", flat.prompt)
                    flat.systemPrompt?.let { put("systemPrompt", it) }
                    if (flat.model.isNotEmpty()) put("model", flat.model)
                    put("maxTokens", flat.maxTokens)
                    put("temperature", flat.temperature)
                    put("jsonMode", flat.jsonMode)
                },
            )
        reply.str("err")?.let { throw AiProviderException(it, retryable = reply.bool("retryable")) }
        val usage = reply["usage"] as? JsonObject ?: JsonObject(emptyMap())
        return AiCompletionResponse(
            content = reply.str("content").orEmpty(),
            promptTokens = usage.int("promptTokens"),
            completionTokens = usage.int("completionTokens"),
            totalTokens = usage.int("totalTokens"),
            model = reply.str("model").orEmpty(),
        )
    }

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
