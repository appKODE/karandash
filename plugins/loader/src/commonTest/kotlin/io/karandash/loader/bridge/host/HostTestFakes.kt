package io.karandash.loader.bridge.host

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAwarePluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.cache.CacheProvider
import io.karandash.api.cache.KarandashCache
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Map-backed cache shared by every name, so tests can read the raw keys the host stored. */
class MapCache : KarandashCache {
    val entries = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = entries[key]

    override suspend fun put(
        key: String,
        value: String,
        ttlMs: Long,
    ) {
        entries[key] = value
    }

    override suspend fun getOrPut(
        key: String,
        compute: suspend () -> String,
    ): String = entries[key] ?: compute().also { entries[key] = it }

    override suspend fun getOrPut(
        key: String,
        dependencies: Map<String, String>,
        compute: suspend () -> String,
    ): String = getOrPut(key, compute)

    override suspend fun invalidate(key: String) {
        entries.remove(key)
    }

    override suspend fun clear() = entries.clear()

    override fun size(): Int = entries.size
}

/** Host context with a recording log, a [MapCache] and an optional [ai] service. */
class HostTestContext(
    override val ai: AiService? = null,
) : PluginContext,
    AiAwarePluginContext {
    val cache = MapCache()
    val logs = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    override val pluginConfig: JsonElement = JsonObject(emptyMap())
    override val hostVersion: String = "0.1.0-test"
    override val workingDirectory: String = "."
    override val cacheProvider: CacheProvider =
        object : CacheProvider {
            override val enabled = true

            override fun getStringCache(
                name: String,
                maxEntries: Int,
            ): KarandashCache = cache
        }

    override fun log(message: String) {
        logs += message
    }

    override fun warn(message: String) {
        warnings += message
    }

    override fun env(name: String): String? = null
}

/** AI service that answers [reply] (after running [onCall]) and records each request. */
class FakeAi(
    private val reply: String = "answer",
    private val onCall: suspend (AiCompletionRequest) -> Unit = {},
) : AiService {
    override val isAvailable = true
    val requests = mutableListOf<AiCompletionRequest>()

    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse {
        requests += request
        onCall(request)
        return AiCompletionResponse(content = reply, promptTokens = 4, completionTokens = 6, totalTokens = 10, model = request.model)
    }
}

/** Budget that records spend; [allow] decides `canSpend`. */
class RecordingBudget(
    private val allow: Boolean = true,
) : AiTokenBudget {
    val spent = mutableListOf<Int>()

    override fun canSpend(estimatedTokens: Int) = allow

    override fun recordSpend(tokens: Int) {
        spent += tokens
    }

    override fun remaining() = if (allow) 1000 else 0

    override fun isExhausted() = !allow
}

fun analyzerContext(
    ai: AiService,
    budget: AiTokenBudget = RecordingBudget(),
): AiAnalyzerContext = AiAnalyzerContext(createTestMergeRequest(), ai, budget, "analyzer-model", JsonObject(emptyMap()))
