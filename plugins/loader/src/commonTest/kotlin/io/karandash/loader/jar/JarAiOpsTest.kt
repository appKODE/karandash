package io.karandash.loader.jar

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AI-tier JAR bridge ops decode the host's encodings, fill the defaults a sparse request leaves out and encode the answer. */
class JarAiOpsTest {
    /** Answers every AI-tier op and records what each call received. */
    private class AiEverything :
        AiInterceptorModule,
        AiPromptProcessorModule,
        AiTokenAllocatorModule,
        AiContextEnricherModule,
        AiAnalyzerModule,
        AiReportModule,
        AiMetricModule {
        override val type = ModuleType.AI_ANALYZER
        override val id = "ai-everything"
        override val name = id
        override val version = "1.0"
        override val defaultEnabled = false
        override val analyzerId = "everything"
        override val reportId = "report"
        override val reportName = "Report"
        override val metricId = "metric"
        override val metricName = "Metric"
        override val unit = "count"
        override val requiredInputs = setOf(ReportInput.FINDINGS)
        val intercepted = mutableListOf<AiInterceptContext>()
        val systemPrompts = mutableListOf<String?>()
        val allocations = mutableListOf<Pair<Int, Map<String, Int>>>()
        val budgets = mutableListOf<BlockBudget>()
        val analyzed = mutableListOf<AiAnalyzerContext>()
        val reported = mutableListOf<ReportContext>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun intercept(
            request: AiCompletionRequest,
            context: AiInterceptContext,
            chain: AiInterceptChain,
        ): AiCompletionResponse {
            intercepted += context
            return if (request.prompt == PROCEED) chain.proceed(request) else response("intercepted")
        }

        override suspend fun processPrompt(
            prompt: String,
            context: AiPromptProcessorContext,
        ) = "$prompt for ${context.model}"

        override suspend fun processSystemPrompt(
            systemPrompt: String?,
            context: AiPromptProcessorContext,
        ): String? {
            systemPrompts += systemPrompt
            return systemPrompt?.uppercase()
        }

        override suspend fun allocate(
            rules: Set<String>,
            totalBudget: Int,
            priorities: Map<String, Int>,
        ): Map<String, Int> {
            allocations += totalBudget to priorities
            return rules.associateWith { totalBudget / rules.size }
        }

        override suspend fun enrich(
            mr: MergeRequest,
            context: AiContextEnricherContext,
        ) = mapOf("rule" to context.ruleId.orEmpty())

        override suspend fun provideBlock(
            mr: MergeRequest,
            changes: List<FileChange>,
            config: kotlinx.serialization.json.JsonElement,
            budget: BlockBudget,
        ): AiContextBlock? {
            budgets += budget
            return changes.firstOrNull()?.let { AiContextBlock("Files", it.path, 1, id) }
        }

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            analyzed += context
            return emptyList()
        }

        override suspend fun generate(context: ReportContext): ReportResult {
            reported += context
            return ReportResult(reportId, JsonNull, "${context.findings.size} findings")
        }

        override suspend fun compute(context: ReportContext) = MetricResult(metricId, context.history.size.toDouble(), unit)
    }

    private class Provider : AiProviderModule {
        override val id = "provider"
        override val name = id
        override val version = "1.0"
        override val providerId = "fake"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun complete(request: AiCompletionRequest) = response(request.prompt.reversed())
    }

    private val module = AiEverything()

    private suspend fun invoke(
        op: String,
        vararg args: String,
        target: io.karandash.api.Module = module,
    ): String? = JarBridgeDispatcher.invoke(target, op, JarInvocation(args.toList()) { error("no context needed") })

    @Test
    fun `an interceptor that answers itself returns its response and one that proceeds returns no answer`() =
        runTest {
            val context = buildJsonObject {
                put("ruleId", "r1")
                put("providerId", "p1")
            }.toString()
            val answered = invoke("aiIntercept", request("hello"), context)
            assertEquals("intercepted", decodeResponse(answered).content)
            assertNull(invoke("aiIntercept", request(PROCEED), ""))
            assertEquals(AiInterceptContext(null, "r1", "p1"), module.intercepted.first())
            assertEquals(AiInterceptContext(null, null, null), module.intercepted.last())
        }

    @Test
    fun `system prompt null crosses the bridge as the sentinel both ways`() =
        runTest {
            assertEquals(BridgeConstants.NULL_SENTINEL, invoke("aiProcessSystemPrompt", BridgeConstants.NULL_SENTINEL, ""))
            assertEquals("SYS", invoke("aiProcessSystemPrompt", "sys", ""))
            assertEquals(listOf(null, "sys"), module.systemPrompts)
            assertEquals("p for m", invoke("aiProcessPrompt", "p", """{"model":"m"}"""))
        }

    @Test
    fun `token allocation defaults a blank priority map and an unparsable budget`() =
        runTest {
            assertEquals("""{"a":0}""", invoke("aiAllocateTokens", """["a"]""", "lots", ""))
            assertEquals("""{"a":5,"b":5}""", invoke("aiAllocateTokens", """["a","b"]""", "10", """{"a":2}"""))
            assertEquals(listOf(0 to emptyMap(), 10 to mapOf("a" to 2)), module.allocations)
        }

    @Test
    fun `provide block defaults blank changes and budget and passes a no block answer through`() =
        runTest {
            assertNull(invoke("aiProvideBlock", mrJson(), "", "", ""))
            val changes = """[{"path":"a.kt","changeType":"MODIFIED"}]"""
            val block = KarandashJson.Default.decodeFromString(
                AiContextBlock.serializer(),
                checkNotNull(invoke("aiProvideBlock", mrJson(), changes, "{}", """{"maxChars":40}""")),
            )
            assertEquals("a.kt", block.content)
            assertEquals(listOf(BlockBudget.UNLIMITED, BlockBudget(40)), module.budgets)
            assertEquals("""{"rule":"r"}""", invoke("aiEnrichContext", mrJson(), """{"ruleId":"r"}"""))
        }

    @Test
    fun `an analyzer sees the host keys lifted out of its config and a fixed budget without a live service`() =
        runTest {
            val config = """{"threshold":3,"extraInstructions":"be brief","resolvedPrompt":"P","aiServiceAvailable":true}"""
            assertEquals("[]", invoke("aiAnalyze", mrJson(), """{"remaining":50}""", "model-x", config))
            invoke("aiAnalyze", mrJson(), "", "model-y", "")
            val (full, sparse) = module.analyzed
            assertEquals(JsonObject(mapOf("threshold" to JsonPrimitive(3))), full.config)
            assertEquals("be brief", full.extraInstructions)
            assertEquals("P", full.resolvedPrompt)
            assertEquals("model-x", full.model)
            assertFalse(full.aiService.isAvailable)
            assertTrue(full.budget.canSpend(50))
            assertFalse(full.budget.canSpend(51))
            assertEquals(50, full.budget.remaining())
            assertFalse(full.budget.isExhausted())
            assertTrue(sparse.budget.isExhausted())
            assertEquals(JsonObject(emptyMap()), sparse.config)
            assertNull(sparse.extraInstructions)
        }

    @Test
    fun `report and metric contexts default missing findings config and history`() =
        runTest {
            val sparse = """{"mr":${mrJson()}}"""
            val report = KarandashJson.Default.decodeFromString(ReportResult.serializer(), checkNotNull(invoke("generate", sparse)))
            assertEquals("0 findings", report.formatted)
            val withHistory = """{"mr":${mrJson()},"history":[1,2],"config":{"k":1}}"""
            val metric = KarandashJson.Default.decodeFromString(MetricResult.serializer(), checkNotNull(invoke("compute", withHistory)))
            assertEquals(2.0, metric.value)
            assertEquals(JsonNull, module.reported.single().config)
        }

    @Test
    fun `property ops encode absent optional values as empty strings`() =
        runTest {
            val provider = Provider()
            assertEquals("", invoke("apiKeyEnvVar", target = provider))
            assertEquals("", invoke("baseUrlEnvVar", target = provider))
            assertEquals("fake", invoke("providerId", target = provider))
            assertEquals("olleh", decodeResponse(invoke("complete", request("hello"), target = provider)).content)
            assertEquals("", invoke("defaultPrompt"))
            assertEquals("false", invoke("enabledByDefault"))
            assertEquals("everything", invoke("featureKey"))
        }

    private companion object {
        const val PROCEED = "proceed"

        fun response(content: String) = AiCompletionResponse(content, 0, 0, 0, "m")

        fun request(prompt: String): String =
            KarandashJson.Default.encodeToString(AiCompletionRequest.serializer(), AiCompletionRequest(prompt = prompt, model = "m"))

        fun decodeResponse(value: String?): AiCompletionResponse =
            KarandashJson.Default.decodeFromString(AiCompletionResponse.serializer(), checkNotNull(value))

        fun mrJson(): String = KarandashJson.Default.encodeToString(MergeRequest.serializer(), createTestMergeRequest())
    }
}
