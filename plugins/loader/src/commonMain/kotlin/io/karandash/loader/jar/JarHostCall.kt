package io.karandash.loader.jar

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.cache.CacheProvider
import io.karandash.api.cache.KarandashCache
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The guest end of the JAR session's host-service channel (`HostServiceDispatcher` on the host): one module's calls,
 * answered with the service's JSON reply or `{"err": ...}`. Only a long-lived session has one; a per-request JVM
 * keeps the local fallbacks.
 */
internal interface JarHostCall {
    /** One host-service call and its reply. */
    suspend fun call(
        service: String,
        request: JsonObject,
    ): JsonObject

    /** As [call] from a caller that cannot suspend (`PluginContext.resolvePattern`). */
    fun callBlocking(
        service: String,
        request: JsonObject,
    ): JsonObject

    /** A call whose reply nobody waits for (`log`). */
    fun notify(
        service: String,
        request: JsonObject,
    )
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The host's cache; the host prefixes every key with the module id. */
internal class JarHostCacheProvider(
    private val host: JarHostCall,
) : CacheProvider {
    override val enabled: Boolean = true

    override fun getStringCache(
        name: String,
        maxEntries: Int,
    ): KarandashCache = JarHostCache(host, name)
}

// limit: invalidate/clear/size are not host services; add them to the channel when a guest needs them
private class JarHostCache(
    private val host: JarHostCall,
    private val name: String,
) : KarandashCache {
    override suspend fun get(key: String): String? =
        host
            .call(
                "cache.get",
                buildJsonObject {
                    put("cache", name)
                    put("key", key)
                },
            ).text("value")

    override suspend fun put(
        key: String,
        value: String,
        ttlMs: Long,
    ) {
        host.call(
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

/**
 * `ai.complete` through the host: its provider, interceptors and (inside `analyze`) its budget and context blocks.
 * Without a host ([host] null) or when the host reports no service, it is unavailable.
 */
internal class JarHostAiService(
    private val host: JarHostCall?,
    override val isAvailable: Boolean = host != null,
) : AiService {
    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        val channel = host ?: error("The host AiService is not available over a per-request JAR bridge")
        val flat = request.withContextInPrompt()
        return decode(
            channel.call(
                "ai.complete",
                buildJsonObject {
                    put("prompt", flat.prompt)
                    flat.systemPrompt?.let { put("systemPrompt", it) }
                    if (flat.model.isNotEmpty()) put("model", flat.model)
                    put("maxTokens", flat.maxTokens)
                    put("temperature", flat.temperature)
                    put("jsonMode", flat.jsonMode)
                },
            ),
        )
    }

    companion object {
        /** An `ai.complete` / `ai.proceed` reply as a response; `{err, retryable}` throws [AiProviderException]. */
        fun decode(reply: JsonObject): AiCompletionResponse {
            reply.text("err")?.let {
                throw AiProviderException(
                    it,
                    retryable =
                        (reply["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            }
            val usage = reply["usage"] as? JsonObject ?: JsonObject(emptyMap())
            return AiCompletionResponse(
                content = reply.text("content").orEmpty(),
                promptTokens = usage.int("promptTokens"),
                completionTokens = usage.int("completionTokens"),
                totalTokens = usage.int("totalTokens"),
                model = reply.text("model").orEmpty(),
            )
        }

        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    }
}
