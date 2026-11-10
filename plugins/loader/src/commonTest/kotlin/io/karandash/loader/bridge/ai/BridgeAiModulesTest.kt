package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportResult
import io.karandash.api.context.EnrichmentContext
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each AI bridge module: capability absent, guest reply, guest returning nothing, and guest failure. */
class BridgeAiModulesTest {
    private class Transport(
        val results: Map<String, String?> = emptyMap(),
        val fail: Boolean = false,
        val props: Map<String, String> = emptyMap(),
        val capable: Boolean = true,
    ) : BridgeTransport {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val errors = mutableListOf<String>()

        override suspend fun call(
            method: String,
            vararg args: String,
        ): String? {
            calls += method to args.toList()
            check(!fail) { "guest failed" }
            return results[method]
        }

        override suspend fun callVoid(
            method: String,
            vararg args: String,
        ) {
            calls += method to args.toList()
        }

        override fun getProperty(name: String): String = props[name].orEmpty()

        override fun getIntProperty(
            name: String,
            default: Int,
        ): Int = props[name]?.toInt() ?: default

        override fun getBoolProperty(
            name: String,
            default: Boolean,
        ): Boolean = props[name]?.toBoolean() ?: default

        override fun hasCapability(name: String): Boolean = capable

        override fun logError(
            method: String,
            exception: Exception,
        ) {
            errors += method
        }
    }

    private object Budget : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int) = true

        override fun recordSpend(tokens: Int) = Unit

        override fun remaining() = 42

        override fun isExhausted() = false
    }

    private object Service : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ) = RESPONSE
    }

    @Test
    fun `redactor passes text through unless the guest rewrites it`() =
        runTest {
            val ctx = AiRedactorContext("r", "p")

            assertEquals("t", BridgeAiRedactorModule(Transport(capable = false)).redact("t", ctx))
            assertEquals("x", BridgeAiRedactorModule(Transport(mapOf("aiRedact" to "x"))).redact("t", ctx))
            assertEquals("t", BridgeAiRedactorModule(Transport()).redact("t", ctx))
            val failing = Transport(fail = true)
            assertEquals("t", BridgeAiRedactorModule(failing).redact("t", ctx))
            assertEquals(listOf("aiRedact"), failing.errors)
            val module = BridgeAiRedactorModule(Transport(props = mapOf("redactorPriority" to "7")))
            assertEquals(7 to ModuleType.AI_REDACTOR, module.redactorPriority to module.type)
        }

    @Test
    fun `prompt processor keeps the prompt unless the guest rewrites it`() =
        runTest {
            val ctx = AiPromptProcessorContext("r", "m", null)
            val rewriting = Transport(mapOf("aiProcessPrompt" to "P2", "aiProcessSystemPrompt" to "S2"))

            BridgeAiPromptProcessorModule(rewriting).let {
                assertEquals("P2" to "S2", it.processPrompt("P", ctx) to it.processSystemPrompt("S", ctx))
            }
            BridgeAiPromptProcessorModule(Transport(capable = false)).let {
                assertEquals("P" to "S", it.processPrompt("P", ctx) to it.processSystemPrompt("S", ctx))
            }
            BridgeAiPromptProcessorModule(Transport()).let {
                assertEquals("P" to "S", it.processPrompt("P", ctx) to it.processSystemPrompt("S", ctx))
            }
            val failing = Transport(fail = true)
            BridgeAiPromptProcessorModule(failing).let {
                assertEquals("P" to "S", it.processPrompt("P", ctx) to it.processSystemPrompt("S", ctx))
            }
            assertEquals(listOf("aiProcessPrompt", "aiProcessSystemPrompt"), failing.errors)
        }

    @Test
    fun `system prompt null travels as the sentinel both ways`() =
        runTest {
            val ctx = AiPromptProcessorContext("r", "m", "p")
            val transport = Transport(mapOf("aiProcessSystemPrompt" to BridgeConstants.NULL_SENTINEL))

            assertNull(BridgeAiPromptProcessorModule(transport).processSystemPrompt(null, ctx))
            assertEquals(
                BridgeConstants.NULL_SENTINEL,
                transport.calls
                    .single()
                    .second
                    .first()
            )
        }

    @Test
    fun `interceptor proceeds down the chain unless the guest answers`() =
        runTest {
            val ctx = AiInterceptContext("src", "r", "p")
            val chain = AiInterceptChain { CHAINED }
            val answered = Transport(mapOf("aiIntercept" to RESPONSE_JSON))

            assertEquals(RESPONSE, BridgeAiInterceptorModule(answered).intercept(REQUEST, ctx, chain))
            assertEquals(CHAINED, BridgeAiInterceptorModule(Transport(capable = false)).intercept(REQUEST, ctx, chain))
            assertEquals(CHAINED, BridgeAiInterceptorModule(Transport()).intercept(REQUEST, ctx, chain))
            val failing = Transport(fail = true)
            assertEquals(CHAINED, BridgeAiInterceptorModule(failing).intercept(REQUEST, ctx, chain))
            assertEquals(listOf("aiIntercept"), failing.errors)
            assertEquals(100, BridgeAiInterceptorModule(Transport()).interceptorPriority)
        }

    @Test
    fun `response transformer keeps the response unless the guest replaces it`() =
        runTest {
            val ctx = AiResponseTransformerContext("r", "p")
            val replacing = Transport(mapOf("aiTransformResponse" to RESPONSE_JSON))

            assertEquals(RESPONSE, BridgeAiResponseTransformerModule(replacing).transform(CHAINED, REQUEST, ctx))
            assertEquals(CHAINED, BridgeAiResponseTransformerModule(Transport(capable = false)).transform(CHAINED, REQUEST, ctx))
            assertEquals(CHAINED, BridgeAiResponseTransformerModule(Transport()).transform(CHAINED, REQUEST, ctx))
            val failing = Transport(fail = true)
            assertEquals(CHAINED, BridgeAiResponseTransformerModule(failing).transform(CHAINED, REQUEST, ctx))
            assertEquals(listOf("aiTransformResponse"), failing.errors)
        }

    @Test
    fun `provider returns an empty response when the guest gives none`() =
        runTest {
            val empty = AiCompletionResponse("", 0, 0, 0, "")
            val props = mapOf("id" to "guest", "apiKeyEnvVar" to "KEY", "defaultModel" to "dm")

            assertEquals(RESPONSE, BridgeAiProviderModule(Transport(mapOf("complete" to RESPONSE_JSON))).complete(REQUEST))
            assertEquals(empty, BridgeAiProviderModule(Transport()).complete(REQUEST))
            val failing = Transport(fail = true)
            assertEquals(empty, BridgeAiProviderModule(failing).complete(REQUEST))
            assertEquals(listOf("complete"), failing.errors)
            val module = BridgeAiProviderModule(Transport(props = props))
            val ids = listOf(module.providerId, module.defaultModel, module.apiKeyEnvVar, module.baseUrlEnvVar)
            assertEquals(listOf("guest", "dm", "KEY", null), ids)
        }

    @Test
    fun `token allocator decodes the guest allocation`() =
        runTest {
            val allocating = Transport(mapOf("aiAllocateTokens" to """{"a":10,"b":"x"}"""))

            assertEquals(mapOf("a" to 10), BridgeAiTokenAllocatorModule(allocating).allocate(setOf("a", "b"), 20, mapOf("a" to 1)))
            assertEquals(listOf("""["a","b"]""", "20", """{"a":1}"""), allocating.calls.single().second)
            assertEquals(emptyMap(), BridgeAiTokenAllocatorModule(Transport(capable = false)).allocate(setOf("a"), 1, emptyMap()))
            assertEquals(emptyMap(), BridgeAiTokenAllocatorModule(Transport()).allocate(setOf("a"), 1, emptyMap()))
            val failing = Transport(fail = true)
            assertEquals(emptyMap(), BridgeAiTokenAllocatorModule(failing).allocate(setOf("a"), 1, emptyMap()))
            assertEquals(listOf("aiAllocateTokens"), failing.errors)
        }

    @Test
    fun `context enricher decodes the guest map`() =
        runTest {
            val ctx = AiContextEnricherContext("r", emptyMap())
            val mr = createTestMergeRequest()
            val enriching = Transport(mapOf("aiEnrichContext" to """{"k":"v","n":1}"""))

            assertEquals(mapOf("k" to "v", "n" to "1"), BridgeAiContextEnricherModule(enriching).enrich(mr, ctx))
            assertEquals(emptyMap(), BridgeAiContextEnricherModule(Transport(capable = false)).enrich(mr, ctx))
            assertEquals(emptyMap(), BridgeAiContextEnricherModule(Transport()).enrich(mr, ctx))
            val failing = Transport(fail = true)
            assertEquals(emptyMap(), BridgeAiContextEnricherModule(failing).enrich(mr, ctx))
            assertEquals(listOf("aiEnrichContext"), failing.errors)
        }

    @Test
    fun `analyzer sends budget and config and decodes findings`() =
        runTest {
            val findings = """[{"severity":"WARNING","message":"m","rule":"r"}]"""
            val analyzing = Transport(mapOf("aiAnalyze" to findings))
            val ctx = AiAnalyzerContext(
                mr = createTestMergeRequest(),
                aiService = Service,
                budget = Budget,
                model = "m1",
                config = JsonObject(mapOf("level" to JsonPrimitive(2))),
                extraInstructions = "be brief",
                resolvedPrompt = "prompt",
            )

            assertEquals(listOf(Finding(Severity.WARNING, "m", "r")), BridgeAiAnalyzerModule(analyzing).analyze(ctx))
            val args = analyzing.calls.single().second
            assertEquals("""{"remaining":42}""", args[1])
            assertEquals("m1", args[2])
            val config = KarandashJson.Default.parseToJsonElement(args[3]).jsonObject
            val sent = listOf("level", "aiServiceAvailable", "extraInstructions", "resolvedPrompt")
                .map { config.getValue(it).jsonPrimitive.content }
            assertEquals(listOf("2", "true", "be brief", "prompt"), sent)
            assertEquals(emptyList(), BridgeAiAnalyzerModule(Transport(capable = false)).analyze(ctx))
            assertEquals(emptyList(), BridgeAiAnalyzerModule(Transport()).analyze(ctx))
            val failing = Transport(mapOf("aiAnalyze" to "not json"))
            assertEquals(emptyList(), BridgeAiAnalyzerModule(failing).analyze(ctx))
            assertEquals(listOf("aiAnalyze"), failing.errors)
        }

    @Test
    fun `analyzer skip result reports the reason and yields no findings`() =
        runTest {
            val skipped = mutableListOf<String>()
            val ctx = AiAnalyzerContext(createTestMergeRequest(), Service, Budget, "m1", JsonObject(emptyMap()), onSkip = { skipped += it })
            val module = BridgeAiAnalyzerModule(Transport(mapOf("aiAnalyze" to """{"skip":"no diff"}""")))
            assertEquals(emptyList(), module.analyze(ctx))
            assertEquals(listOf("no diff"), skipped)
        }

    @Test
    fun `analyzer ids fall back to the module id`() {
        val module = BridgeAiAnalyzerModule(Transport(props = mapOf("id" to "guest")))

        assertEquals(listOf("guest", "guest", null), listOf(module.analyzerId, module.featureKey, module.defaultPrompt))
        assertEquals(false, module.defaultEnabled)
    }

    @Test
    fun `metric decodes the guest result and logs a reply it cannot read`() =
        runTest {
            val zero = MetricResult("risk", 0.0, "pts")
            val props = mapOf("metricId" to "risk", "unit" to "pts")
            val computing = Transport(mapOf("compute" to """{"metricId":"risk","value":3.5,"unit":"pts"}"""), props = props)

            assertEquals(MetricResult("risk", 3.5, "pts"), BridgeAiMetricModule(computing).compute(REPORT_CONTEXT))
            assertTrue(
                "findings" in computing.calls
                    .single()
                    .second
                    .single()
            )
            assertEquals(zero, BridgeAiMetricModule(Transport(capable = false, props = props)).compute(REPORT_CONTEXT))
            assertEquals(zero, BridgeAiMetricModule(Transport(props = props)).compute(REPORT_CONTEXT))
            val unreadable = Transport(mapOf("compute" to "not json"), props = props)
            assertEquals(zero, BridgeAiMetricModule(unreadable).compute(REPORT_CONTEXT))
            assertEquals(listOf("compute"), unreadable.errors)
        }

    @Test
    fun `report decodes the guest result or falls back to its fields`() =
        runTest {
            val props = mapOf("reportId" to "sum")
            val empty = ReportResult("sum", JsonObject(emptyMap()), "")
            val full = Transport(mapOf("generate" to """{"reportId":"sum","data":{"a":1},"formatted":"F"}"""), props = props)
            val partial = Transport(mapOf("generate" to """{"formatted":"F"}"""), props = props)

            val expected = ReportResult("sum", JsonObject(mapOf("a" to JsonPrimitive(1))), "F")
            assertEquals(expected, BridgeAiReportModule(full).generate(REPORT_CONTEXT))
            assertEquals(ReportResult("sum", JsonObject(emptyMap()), "F"), BridgeAiReportModule(partial).generate(REPORT_CONTEXT))
            assertEquals(empty, BridgeAiReportModule(Transport(mapOf("generate" to "[1]"), props = props)).generate(REPORT_CONTEXT))
            assertEquals(empty, BridgeAiReportModule(Transport(capable = false, props = props)).generate(REPORT_CONTEXT))
            assertEquals(empty, BridgeAiReportModule(Transport(props = props)).generate(REPORT_CONTEXT))
            val failing = Transport(fail = true, props = props)
            assertEquals(empty, BridgeAiReportModule(failing).generate(REPORT_CONTEXT))
            assertEquals(listOf("generate"), failing.errors)
        }

    private companion object {
        val REQUEST = AiCompletionRequest(prompt = "hi", model = "m")
        val RESPONSE = AiCompletionResponse("guest", 1, 2, 3, "m")
        val CHAINED = AiCompletionResponse("chain", 0, 0, 0, "m")
        const val RESPONSE_JSON = """{"content":"guest","promptTokens":1,"completionTokens":2,"totalTokens":3,"model":"m"}"""
        val REPORT_CONTEXT = ReportContext(
            findings = listOf(Finding(Severity.INFO, "f", "r")),
            mr = createTestMergeRequest(),
            enrichment = EnrichmentContext.EMPTY,
            config = JsonObject(emptyMap()),
        )
    }
}
