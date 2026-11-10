package io.karandash.loader.jar

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.ai.BlockBudget
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportResult
import io.karandash.helpers.guest.GuestCodec
import io.karandash.helpers.guest.GuestCodec.element
import io.karandash.helpers.guest.GuestCodec.encodeFindings
import io.karandash.helpers.guest.GuestCodec.json
import io.karandash.helpers.guest.GuestCodec.mr
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.models.FileChange
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * JAR bridge ops of the AI tier. In a long-lived session ([JarInvocation.host] set) analyzers complete through the
 * host's `ai.complete` (its provider, budget and context blocks) and an interceptor's `proceed` runs the rest of the
 * host's chain (`ai.proceed`). A per-request JVM has no channel: analyzers see an unavailable service, and an
 * interceptor that calls `proceed` answers "no change" so the host proceeds with the original request. Either way the
 * budget is fixed at the host's `remaining` (the host records the spend) and the upstream enrichment context stays
 * on the host.
 */
internal object JarAiOps {
    private const val PROVIDER_ID = "providerId"
    private const val RULE_ID = "ruleId"

    private val stages: Map<String, JarBridgeOp> =
        mapOf(
            PROVIDER_ID to jarOp<AiProviderModule> { providerId },
            "defaultModel" to jarOp<AiProviderModule> { defaultModel },
            "apiKeyEnvVar" to jarOp<AiProviderModule> { apiKeyEnvVar.orEmpty() },
            "baseUrlEnvVar" to jarOp<AiProviderModule> { baseUrlEnvVar.orEmpty() },
            "complete" to jarOp<AiProviderModule> { encodeResponse(complete(request(it[0]))) },
            "aiIntercept" to jarOp<AiInterceptorModule> { call ->
                val sent = element(call[1])
                val context = AiInterceptContext(sent.text("sourceModuleId"), sent.text(RULE_ID), sent.text(PROVIDER_ID))
                val host = call.host
                if (host != null) {
                    encodeResponse(intercept(request(call[0]), context) { proceed(host, it) })
                } else {
                    val proceeded = mutableListOf<AiCompletionRequest>()
                    val response = intercept(request(call[0]), context) { forwarded ->
                        proceeded += forwarded
                        AiCompletionResponse("", 0, 0, 0, forwarded.model)
                    }
                    response.takeIf { proceeded.isEmpty() }?.let(::encodeResponse)
                }
            },
            "aiProcessPrompt" to jarOp<AiPromptProcessorModule> { processPrompt(it[0], promptContext(it[1])) },
            "aiProcessSystemPrompt" to jarOp<AiPromptProcessorModule> { call ->
                val prompt = call[0].takeUnless { it == BridgeConstants.NULL_SENTINEL }
                processSystemPrompt(prompt, promptContext(call[1])) ?: BridgeConstants.NULL_SENTINEL
            },
            "aiTransformResponse" to jarOp<AiResponseTransformerModule> { call ->
                val context = element(call[2]).let { AiResponseTransformerContext(it.text(RULE_ID), it.text(PROVIDER_ID)) }
                encodeResponse(transform(json.decodeFromString(AiCompletionResponse.serializer(), call[0]), request(call[1]), context))
            },
            "aiRedact" to jarOp<AiRedactorModule> { call ->
                redact(call[0], element(call[1]).let { AiRedactorContext(it.text(RULE_ID), it.text(PROVIDER_ID)) })
            },
            "aiAllocateTokens" to jarOp<AiTokenAllocatorModule> { call ->
                val priorities = json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), call[2].ifBlank { "{}" })
                val allocation = allocate(GuestCodec.strings(call[0]).toSet(), call[1].toIntOrNull() ?: 0, priorities)
                json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), allocation)
            },
        )

    private val analysis: Map<String, JarBridgeOp> =
        mapOf(
            "analyzerId" to jarOp<AiAnalyzerModule> { analyzerId },
            "featureKey" to jarOp<AiAnalyzerModule> { featureKey },
            "defaultPrompt" to jarOp<AiAnalyzerModule> { defaultPrompt.orEmpty() },
            "enabledByDefault" to jarOp<AiAnalyzerModule> { defaultEnabled.toString() },
            "aiAnalyze" to jarOp<AiAnalyzerModule> { call ->
                val skipped = mutableListOf<String>()
                val findings = analyze(analyzerContext(call) { skipped += it })
                // `{"skip": reason}`: read by the host's BridgeAiAnalyzerModule as context.skip(reason)
                skipped.lastOrNull()?.let { buildJsonObject { put("skip", it) }.toString() } ?: encodeFindings(findings)
            },
            "aiEnrichContext" to jarOp<AiContextEnricherModule> { call ->
                val enriched = enrich(mr(call[0]), AiContextEnricherContext(element(call[1]).text(RULE_ID), emptyMap()))
                json.encodeToString(MapSerializer(String.serializer(), String.serializer()), enriched)
            },
            "aiProvideBlock" to jarOp<AiContextEnricherModule> { call ->
                val changes = json.decodeFromString(ListSerializer(FileChange.serializer()), call[1].ifBlank { "[]" })
                val budget = call[3].takeIf { it.isNotBlank() }?.let { json.decodeFromString(BlockBudget.serializer(), it) }
                    ?: BlockBudget.UNLIMITED
                provideBlock(mr(call[0]), changes, element(call[2]), budget)?.let { json.encodeToString(AiContextBlock.serializer(), it) }
            },
            "reportId" to jarOp<AiReportModule> { reportId },
            "reportName" to jarOp<AiReportModule> { reportName },
            "generate" to
                jarOp<AiReportModule> {
                    json.encodeToString(
                        ReportResult.serializer(),
                        generate(BridgeContextSerializer.deserializeReport(it[0]))
                    )
                },
            "metricId" to jarOp<AiMetricModule> { metricId },
            "metricName" to jarOp<AiMetricModule> { metricName },
            "unit" to jarOp<AiMetricModule> { unit },
            "compute" to
                jarOp<AiMetricModule> {
                    json.encodeToString(
                        MetricResult.serializer(),
                        compute(BridgeContextSerializer.deserializeReport(it[0]))
                    )
                },
        )

    val OPS: Map<String, JarBridgeOp> = stages + analysis

    /** Keys the host merges into the analyzer's config next to the rule config. */
    private val analyzerHostKeys = setOf("aiServiceAvailable", "extraInstructions", "instructions", "resolvedPrompt")

    private fun request(value: String): AiCompletionRequest = json.decodeFromString(AiCompletionRequest.serializer(), value)

    private fun encodeResponse(response: AiCompletionResponse): String = json.encodeToString(AiCompletionResponse.serializer(), response)

    private fun JsonElement.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun promptContext(value: String): AiPromptProcessorContext =
        element(value).let { AiPromptProcessorContext(it.text(RULE_ID), it.text("model").orEmpty(), it.text(PROVIDER_ID)) }

    /**
     * The analyzer's context. With a host channel, completions go through the host's `ai.complete`, which adds the
     * context blocks itself, so the analyzer gets the plain `instructions`; without one it gets `extraInstructions`
     * (the blocks inlined) and an unavailable service.
     */
    private fun analyzerContext(
        call: JarInvocation,
        onSkip: (String) -> Unit,
    ): AiAnalyzerContext {
        val config = element(call[3]) as? JsonObject ?: JsonObject(emptyMap())
        val remaining = ((element(call[1]) as? JsonObject)?.get("remaining") as? JsonPrimitive)?.intOrNull ?: 0
        val host = call.host
        val available = host != null && (config["aiServiceAvailable"] as? JsonPrimitive)?.booleanOrNull == true
        return AiAnalyzerContext(
            mr = mr(call[0]),
            aiService = JarHostAiService(host, available),
            budget = FixedBudget(remaining),
            model = call[2],
            config = JsonObject(config - analyzerHostKeys),
            extraInstructions = config.text(if (host != null) "instructions" else "extraInstructions"),
            resolvedPrompt = config.text("resolvedPrompt"),
            onSkip = onSkip,
        )
    }

    /** `ai.proceed`: the rest of the host's interceptor chain, with the request as the guest forwarded it. */
    private suspend fun proceed(
        host: JarHostCall,
        forwarded: AiCompletionRequest,
    ): AiCompletionResponse {
        val sent = json.encodeToJsonElement(AiCompletionRequest.serializer(), forwarded) as? JsonObject ?: JsonObject(emptyMap())
        return JarHostAiService.decode(host.call("ai.proceed", sent))
    }

    /** The host's budget as the host reported it; spending is tracked on the host. */
    private class FixedBudget(
        private val remaining: Int,
    ) : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int): Boolean = estimatedTokens <= remaining

        override fun recordSpend(tokens: Int) = Unit

        override fun remaining(): Int = remaining

        override fun isExhausted(): Boolean = remaining <= 0
    }
}
