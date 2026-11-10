package io.karandash.loader.wasm

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.loader.bridge.ai.analyzerConfigJson
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.bridge.transport.WasmTransport
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WASM operations outside the [BridgeAbi][io.karandash.loader.bridge.BridgeAbi] op table, called through
 * [WasmTransport.callI32]: the single-JSON provider and comment shapes, and the AI analyze continuation loop.
 */
internal object WasmBridgeOperations {
    private val json = KarandashJson.Default

    private const val KEY_PROJECT_ID = "projectId"
    private const val KEY_MR_ID = "mrId"
    private const val KEY_MODEL = "model"
    private const val MAX_CONTINUATION = 10
    private const val AI_COMPLETE = "ai.complete"
    private val USAGE_KEYS = listOf("promptTokens", "completionTokens", "totalTokens")

    // --- Single-JSON provider shape (throws on null) ---

    fun fetchMergeRequest(
        transport: WasmTransport,
        moduleId: String,
        projectId: String,
        mrId: String,
    ): MergeRequest {
        val inputJson = json.encodeToString(
            buildJsonObject {
                put(KEY_PROJECT_ID, JsonPrimitive(projectId))
                put(KEY_MR_ID, JsonPrimitive(mrId))
            },
        )
        val resultJson = transport.readString(transport.callI32(WasmAbi.FETCH_MERGE_REQUEST, listOf(inputJson)))
            ?: error("WASM plugin '$moduleId' returned null from fetchMergeRequest")
        if (resultJson.isEmpty()) error("WASM plugin '$moduleId' returned empty from fetchMergeRequest")
        return json.decodeFromString(resultJson)
    }

    // --- Single-JSON comment shape (null result reads as an empty comment id) ---

    fun postComment(
        transport: WasmTransport,
        projectId: String,
        mrId: String,
        body: String,
    ): String {
        val inputJson = json.encodeToString(
            buildJsonObject {
                put(KEY_PROJECT_ID, JsonPrimitive(projectId))
                put(KEY_MR_ID, JsonPrimitive(mrId))
                put("body", JsonPrimitive(body))
            },
        )
        // A null result is "posted, no id": the same empty id every other transport returns.
        return transport.readString(transport.callI32(WasmAbi.POST_COMMENT, listOf(inputJson))).orEmpty()
    }

    // --- AI Analyze with continuation ---

    /**
     * `karandash_ai_analyze(mr, budget, model, config)` with the same arguments as every other transport, then the
     * `aiRequest` continuation loop (a guest may call `ai.complete` through `host_call` instead).
     * Runs with [host] bound to [context], so both the loop's completions and the guest's own `ai.complete` calls
     * spend this call's budget.
     */
    suspend fun analyze(
        transport: WasmTransport,
        moduleId: String,
        logger: Logger,
        context: AiAnalyzerContext,
        host: HostServiceDispatcher,
    ): List<Finding> =
        host.withAnalyzer(context) {
            val mrJson = json.encodeToString(context.mr)
            val budgetJson = buildJsonObject { put("remaining", JsonPrimitive(context.budget.remaining())) }.toString()
            val args = listOf(mrJson, budgetJson, context.model, analyzerConfigJson(context))
            val resultJson = transport.readString(transport.callI32(WasmAbi.AI_ANALYZE, args)).orEmpty()
            if (resultJson.isEmpty()) emptyList() else processAnalyzeResult(transport, moduleId, logger, resultJson, host)
        }

    /**
     * While the result is an `{"aiRequest": {...}}` object, serves it as an `ai.complete` host call and passes the
     * answer to [WasmAbi.AI_ANALYZE_CONTINUE]. Loops up to [MAX_CONTINUATION] rounds.
     */
    private suspend fun processAnalyzeResult(
        transport: WasmTransport,
        moduleId: String,
        logger: Logger,
        resultJson: String,
        host: HostServiceDispatcher,
    ): List<Finding> {
        var currentJson = resultJson
        var rounds = 0
        while (rounds < MAX_CONTINUATION) {
            val aiReq = (json.parseToJsonElement(currentJson) as? JsonObject)?.get("aiRequest")?.jsonObject
                ?: return json.decodeFromString<List<Finding>>(currentJson)
            val answer = json.parseToJsonElement(host.dispatch(AI_COMPLETE, aiReq.toString())).jsonObject
            answer["err"]?.let {
                logger.warn("[WASM:$moduleId] AI request failed: ${it.jsonPrimitive.content}")
                return emptyList()
            }
            currentJson = transport
                .readString(transport.callI32(WasmAbi.AI_ANALYZE_CONTINUE, listOf(continuationResponse(answer))))
                ?.takeIf { it.isNotEmpty() }
                ?: return emptyList()
            rounds++
        }
        logger.debug("[WASM:$moduleId] AI analyze exceeded max continuation rounds ($MAX_CONTINUATION)")
        return json.decodeFromString<List<Finding>>(currentJson)
    }

    /** The continuation's flat `{content, promptTokens, completionTokens, totalTokens, model}` from an `ai.complete` answer. */
    private fun continuationResponse(answer: JsonObject): String {
        val usage = answer["usage"] as? JsonObject
        return buildJsonObject {
            put("content", answer["content"] ?: JsonPrimitive(""))
            USAGE_KEYS.forEach { put(it, usage?.get(it) ?: JsonPrimitive(0)) }
            put(KEY_MODEL, answer[KEY_MODEL] ?: JsonPrimitive(""))
        }.toString()
    }
}
